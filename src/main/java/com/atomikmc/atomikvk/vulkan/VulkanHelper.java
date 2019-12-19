package com.atomikmc.atomikvk.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static com.atomikmc.atomikvk.glfw.GLFWHelper.INITIAL_WINDOW_HEIGHT;
import static com.atomikmc.atomikvk.glfw.GLFWHelper.INITIAL_WINDOW_WIDTH;
import static java.lang.Math.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugReport.VK_ERROR_VALIDATION_FAILED_EXT;
import static org.lwjgl.vulkan.KHRDedicatedAllocation.VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK11.*;

public class VulkanHelper {
    private static VkInstance vkInstance = null;
    private static VkDevice device = null;
    private static long surface = 0;
    private static SwapChain swapChain = null;
    private static long[] renderFences = null;
    private static long commandPool = 0;
    private static long renderPass = 0;
    private static long[] framebuffers = null;
    private static VkCommandBuffer[] rasterCommandBuffers = null;
    private static long[] imageAcquireSemaphores = null;
    private static long[] renderCompleteSemaphores = null;
    private static DeviceAndQueueFamilies deviceAndQueueFamilies = null;

    private static VkQueue queue = null;

    private static Map<Long, Runnable> waitingFenceActions = new HashMap<>();


    private static int idx = 0;

    public static void setupVulkan(long window) {
        try (MemoryStack stack = stackPush()) {

            // setup necessary extensions
            PointerBuffer pVkRequiredExtensions = glfwGetRequiredInstanceExtensions();
            if (pVkRequiredExtensions == null) {
                throw new AssertionError("Missing list of required Vulkan extensions");
            }

            PointerBuffer pExtensions = stack.mallocPointer(pVkRequiredExtensions.remaining() + 1);
            pExtensions.put(pVkRequiredExtensions);
            pExtensions.put(stack.UTF8(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME));
            pExtensions.flip();

            // setup VK instance creation info
            VkInstanceCreateInfo vkInstanceCreateInfo = VkInstanceCreateInfo.create()
                    .ppEnabledExtensionNames(pExtensions)
                    .pApplicationInfo(VkApplicationInfo.mallocStack(stack).apiVersion(VK_API_VERSION_1_1));

            // create and wrap VkInstance
            PointerBuffer vkInstanceBuffer = stack.mallocPointer(1);
            _CHECK_(vkCreateInstance(vkInstanceCreateInfo, null, vkInstanceBuffer), "Failed to create VkInstance!");
            vkInstance = new VkInstance(vkInstanceBuffer.get(0), vkInstanceCreateInfo);

            // create surface from window
            LongBuffer pSurface = stack.mallocLong(1);
            _CHECK_(glfwCreateWindowSurface(vkInstance, window, null, pSurface), "Failed to create Vulkan surface from window!");
            surface = pSurface.get(0);

            // find all Vulkan-compatible hardware
            IntBuffer pPhysicalDeviceCount = stack.mallocInt(1);
            _CHECK_(vkEnumeratePhysicalDevices(vkInstance, pPhysicalDeviceCount, null), "Could not get physical devices!");
            int physDeviceCount = pPhysicalDeviceCount.get(0);
            PointerBuffer pPhysDevices = stack.mallocPointer(physDeviceCount);
            _CHECK_(vkEnumeratePhysicalDevices(vkInstance, pPhysicalDeviceCount, pPhysDevices), "Could not get physical devices!");

            deviceAndQueueFamilies = chooseGraphicsDevice(vkInstance, surface, physDeviceCount, pPhysDevices);

            int queueFamily = deviceAndQueueFamilies.queuesFamilies.findSingleSuitableQueue();

            device = createVkDevice(deviceAndQueueFamilies, queueFamily);
            queue = createVkQueue(device, queueFamily);
            swapChain = createSwapChain(device, surface, deviceAndQueueFamilies, swapChain);

            commandPool = createCommandPool(0, device, queueFamily);
            renderPass = createRasterRenderPass(device, swapChain);
            framebuffers = createFramebuffers(device, swapChain, renderPass, null);
            rasterCommandBuffers = createRasterCommandBuffers(device, swapChain, framebuffers, commandPool, renderPass);
            createSyncObjects(device, swapChain);
        }
    }

    public static void cleanupVulkan() {
        _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle!");

        if (swapChain != null) {
            for (int i = 0; i < swapChain.images.length; i++) {
                if (imageAcquireSemaphores != null)
                    vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
                if (renderCompleteSemaphores != null)
                    vkDestroySemaphore(device, renderCompleteSemaphores[i], null);
                if (renderFences != null)
                    vkDestroyFence(device, renderFences[i], null);
            }

            if (rasterCommandBuffers != null)
                freeCommandBuffers();

            swapChain.free(device);

            if (framebuffers != null)
                for (long framebuffer : framebuffers)
                    vkDestroyFramebuffer(device, framebuffer, null);

            if (renderPass != 0)
                vkDestroyRenderPass(device, renderPass, null);
            if (commandPool != 0)
                vkDestroyCommandPool(device, commandPool, null);
        }

        if (device != null)
            vkDestroyDevice(device, null);
        if (surface != 0)
            vkDestroySurfaceKHR(vkInstance, surface, null);
        if (vkInstance != null)
            vkDestroyInstance(vkInstance, null);
    }

    public static void update(IntBuffer pImageIndex, int w, int h) {
        if (w != swapChain.width || h != swapChain.height) {
            vkDeviceWaitIdle(device);
            recreateOnResize();
            idx = 0;
        }

        vkWaitForFences(device, renderFences[idx], true, Long.MAX_VALUE);
        vkResetFences(device, renderFences[idx]);

        _CHECK_(vkAcquireNextImageKHR(device, swapChain.swapchain, -1L, imageAcquireSemaphores[idx], VK_NULL_HANDLE,
                pImageIndex), "Failed to acquire image");

        submitAndPresent(pImageIndex.get(0), idx);
        processFinishedFences();
        idx = (idx + 1) % swapChain.images.length;
    }

    private static void submitAndPresent(int imageIndex, int idx) {
        try (MemoryStack stack = stackPush()) {
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo.mallocStack(stack)

                    .pCommandBuffers(stack.pointers(rasterCommandBuffers[idx]))

                    .pWaitSemaphores(stack.longs(imageAcquireSemaphores[idx]))

                    .waitSemaphoreCount(1)

                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))

                    .pSignalSemaphores(stack.longs(renderCompleteSemaphores[idx])), renderFences[idx]), "Failed to submit command buffer");

            _CHECK_(vkQueuePresentKHR(queue, VkPresentInfoKHR.mallocStack(stack)
                    .pWaitSemaphores(stack.longs(renderCompleteSemaphores[idx]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapChain.swapchain))
                    .pImageIndices(stack.ints(imageIndex))), "Failed to present image");
        }
    }

    private static void processFinishedFences() {
        Iterator<Map.Entry<Long, Runnable>> it = waitingFenceActions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Runnable> e = it.next();
            if (vkGetFenceStatus(device, e.getKey()) == VK_SUCCESS) {
                it.remove();
                vkDestroyFence(device, e.getKey(), null);
                e.getValue().run();
            }
        }
    }

    private static void freeCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(rasterCommandBuffers.length);
            for (VkCommandBuffer cb : rasterCommandBuffers)
                pCommandBuffers.put(cb);
            vkFreeCommandBuffers(device, commandPool, pCommandBuffers.flip());
        }
    }

    private static DeviceAndQueueFamilies chooseGraphicsDevice(VkInstance vkInstance, long surface, int physDeviceCount, PointerBuffer pPhysDevices) {
        for (int i = 0; i < physDeviceCount; i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(pPhysDevices.get(i), vkInstance);
            QueueFamilies queuesFamilies = new QueueFamilies();
            try (MemoryStack stack1 = stackPush()) {
                IntBuffer pQueueFamilyPropertyCount = stack1.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyPropertyCount, null);
                VkQueueFamilyProperties.Buffer familyProperties = VkQueueFamilyProperties.create(pQueueFamilyPropertyCount.get(0));
                vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyPropertyCount, familyProperties);

                int queueFamilyIndex = 0;
                for (VkQueueFamilyProperties queueFamilyProps : familyProperties) {
                    IntBuffer pSupported = stack1.mallocInt(1);
                    if (queueFamilyProps.queueCount() < 1) {
                        continue;
                    }
                    vkGetPhysicalDeviceSurfaceSupportKHR(device, queueFamilyIndex, surface, pSupported);

                    if ((queueFamilyProps.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                        queuesFamilies.graphicsFamilies.add(queueFamilyIndex);
                    }

                    if (pSupported.get(0) != 0) {
                        queuesFamilies.presentFamilies.add(queueFamilyIndex);
                    }

                    queueFamilyIndex++;
                }

                VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.mallocStack(stack1);
                vkGetPhysicalDeviceProperties(device, deviceProperties);

                boolean isDiscrete = deviceProperties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
                boolean isRenderable = /*isDiscrete &&*/ !queuesFamilies.graphicsFamilies.isEmpty() && !queuesFamilies.presentFamilies.isEmpty();

                if (isRenderable) {
                    return new DeviceAndQueueFamilies(device, queuesFamilies);
                }
            }
        }
        throw new AssertionError("No GPU Detected");
    }

    private static VkDevice createVkDevice(DeviceAndQueueFamilies deviceAndQueueFamilies, int queueFamily) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pPropertyCount = stack.mallocInt(1);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount, null),
                    "Failed to enumerate device extensions");
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.mallocStack(pPropertyCount.get(0), stack);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount,
                    pProperties),
                    "Failed to enumerate device extensions");

            if (!isExtensionEnabled(pProperties, VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                throw new AssertionError("Missing required extension: " + VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            if (!isExtensionEnabled(pProperties, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME))
                throw new AssertionError("Missing required extension: " + VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);

            PointerBuffer extensions = stack.mallocPointer(2 + 1);
            extensions.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                    .put(stack.UTF8(VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME));
            if (isExtensionEnabled(pProperties, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME)) {
                extensions.put(stack.UTF8(VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME));
            }

            VkDeviceCreateInfo pCreateInfo = VkDeviceCreateInfo.mallocStack(stack)
                    .pQueueCreateInfos(VkDeviceQueueCreateInfo.mallocStack(1, stack)
                            .queueFamilyIndex(queueFamily)
                            .pQueuePriorities(stack.floats(1.0f)))
                    .ppEnabledExtensionNames(extensions.flip());

            PointerBuffer pDevice = stack.mallocPointer(1);
            _CHECK_(vkCreateDevice(deviceAndQueueFamilies.physicalDevice, pCreateInfo, null, pDevice), "Failed to create device");

            return new VkDevice(pDevice.get(0), deviceAndQueueFamilies.physicalDevice, pCreateInfo);
        }
    }

    private static VkQueue createVkQueue(VkDevice device, int queueFamily) {
        try (MemoryStack stack1 = stackPush()) {
            PointerBuffer pQueue = stack1.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            return new VkQueue(pQueue.get(0), device);
        }
    }

    private static SwapChain createSwapChain(VkDevice device, long surface, DeviceAndQueueFamilies deviceAndQueueFamilies, SwapChain swapChain) {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR surfCaps = VkSurfaceCapabilitiesKHR.mallocStack(stack);
            _CHECK_(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(deviceAndQueueFamilies.physicalDevice, surface, surfCaps),
                    "Failed to get physical device surface capabilities");

            IntBuffer count = stack.mallocInt(1);
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, count, null),
                    "Failed to get presentation modes count");
            IntBuffer pPresentModes = stack.mallocInt(count.get(0));
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, count, pPresentModes),
                    "Failed to get presentation modes");

            int imageCount = min(surfCaps.minImageCount() + 1, surfCaps.maxImageCount());

            ColorFormatAndSpace surfaceFormat = determineSurfaceFormat(deviceAndQueueFamilies.physicalDevice, surface);

            Vector2i swapchainExtents = determineSwapchainExtents(surfCaps);
            VkSwapchainCreateInfoKHR pCreateInfo = VkSwapchainCreateInfoKHR.mallocStack(stack)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageExtent(e -> e.set(swapchainExtents.x, swapchainExtents.y))
                    .imageFormat(surfaceFormat.colorFormat)
                    .imageColorSpace(surfaceFormat.colorSpace)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .preTransform(VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                    .imageArrayLayers(1)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                    .oldSwapchain(swapChain != null ? swapChain.swapchain : VK_NULL_HANDLE)
                    .clipped(true)
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);

            LongBuffer pSwapChain = stack.mallocLong(1);
            _CHECK_(vkCreateSwapchainKHR(device, pCreateInfo, null, pSwapChain), "Failed to create swap chain");
            if (swapChain != null) {
                swapChain.free(device);
            }

            IntBuffer pImageCount = stack.mallocInt(1);
            _CHECK_(vkGetSwapchainImagesKHR(device, pSwapChain.get(0), pImageCount, null),
                    "Failed to get swapchain images count");
            LongBuffer pSwapchainImages = stack.mallocLong(pImageCount.get(0));
            _CHECK_(vkGetSwapchainImagesKHR(device, pSwapChain.get(0), pImageCount, pSwapchainImages),
                    "Failed to get swapchain images");

            long[] images = new long[pImageCount.get(0)];
            long[] imageViews = new long[pImageCount.get(0)];
            pSwapchainImages.get(images, 0, images.length);
            LongBuffer pImageView = stack.mallocLong(1);
            for (int i = 0; i < pImageCount.get(0); i++) {
                _CHECK_(vkCreateImageView(device, VkImageViewCreateInfo.mallocStack(stack)
                        .format(surfaceFormat.colorFormat)
                        .viewType(VK_IMAGE_TYPE_2D)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1))
                        .image(images[i]), null, pImageView), "Failed to create image view");
                imageViews[i] = pImageView.get(0);
            }
            return new SwapChain(pSwapChain.get(0), images, imageViews, swapchainExtents.x, swapchainExtents.y, surfaceFormat);

        }
    }

    private static ColorFormatAndSpace determineSurfaceFormat(VkPhysicalDevice physicalDevice, long surface) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            _CHECK_(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null),
                    "Failed to get device surface formats count");
            VkSurfaceFormatKHR.Buffer pSurfaceFormats = VkSurfaceFormatKHR.mallocStack(count.get(0), stack);
            _CHECK_(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, pSurfaceFormats),
                    "Failed to get device surface formats");

            int colorFormat;

            if (pSurfaceFormats.remaining() == 1 && pSurfaceFormats.get(0).format() == VK_FORMAT_UNDEFINED)
                colorFormat = VK_FORMAT_B8G8R8A8_UNORM;
            else
                colorFormat = pSurfaceFormats.get(0).format();

            int colorSpace = pSurfaceFormats.get(0).colorSpace();

            return new ColorFormatAndSpace(colorFormat, colorSpace);
        }
    }

    private static Vector2i determineSwapchainExtents(VkSurfaceCapabilitiesKHR surfCaps) {
        VkExtent2D extent = surfCaps.currentExtent();
        Vector2i ret = new Vector2i(extent.width(), extent.height());
        if (extent.width() == -1) {
            ret.set(max(min(INITIAL_WINDOW_WIDTH, surfCaps.maxImageExtent().width()), surfCaps.minImageExtent().width()),
                    max(min(INITIAL_WINDOW_HEIGHT, surfCaps.maxImageExtent().height()), surfCaps.minImageExtent().height()));
        }
        return ret;
    }

    private static long createCommandPool(int flags, VkDevice device, int queueFamily) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pCommandPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, VkCommandPoolCreateInfo.mallocStack(stack)
                    .queueFamilyIndex(queueFamily)
                    .flags(flags), null, pCommandPool), "Failed to create command queue");
            return pCommandPool.get(0);
        }
    }

    private static long createRasterRenderPass(VkDevice device, SwapChain swapChain) {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.callocStack(stack)
                    .pAttachments(VkAttachmentDescription.mallocStack(1, stack).apply(0, d -> d
                            .format(swapChain.surfaceFormat.colorFormat)
                            .samples(VK_SAMPLE_COUNT_1_BIT)
                            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                            .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)))
                    .pSubpasses(VkSubpassDescription.callocStack(1, stack)
                            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                            .colorAttachmentCount(1)
                            .pColorAttachments(VkAttachmentReference.mallocStack(1, stack)
                                    .attachment(0)
                                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)));
            LongBuffer pRenderPass = stack.mallocLong(1);
            _CHECK_(vkCreateRenderPass(device, renderPassInfo, null, pRenderPass), "Failed to create render pass!");
            return pRenderPass.get(0);
        }
    }

    private static long[] createFramebuffers(VkDevice device, SwapChain swapchain, long renderPass, long[] framebuffers) {
        if (framebuffers != null) {
            for (long framebuffer : framebuffers)
                vkDestroyFramebuffer(device, framebuffer, null);
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer pAttachments = stack.mallocLong(1);
            VkFramebufferCreateInfo fci = VkFramebufferCreateInfo.mallocStack(stack)
                    .pAttachments(pAttachments)
                    .width(swapchain.width)
                    .height(swapchain.height)
                    .layers(1)
                    .renderPass(renderPass);
            framebuffers = new long[swapchain.images.length];
            LongBuffer pFramebuffer = stack.mallocLong(1);
            for (int i = 0; i < swapchain.images.length; i++) {
                pAttachments.put(0, swapchain.imageViews[i]);
                _CHECK_(vkCreateFramebuffer(device, fci, null, pFramebuffer), "Failed to create framebuffer");
                framebuffers[i] = pFramebuffer.get(0);
            }
            return framebuffers;
        }
    }

    private static VkCommandBuffer[] createRasterCommandBuffers(VkDevice device, SwapChain swapchain, long[] framebuffers, long commandPool, long renderPass) {
        try (MemoryStack stack = stackPush()) {
            VkClearValue.Buffer clearValues = VkClearValue.mallocStack(1, stack);
            clearValues.apply(0, v -> v.color().float32(0, 0.5f).float32(1, 0.7f).float32(2, 0.9f).float32(3, 1));

            VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo.mallocStack(stack).renderPass(renderPass)
                    .pClearValues(clearValues)
                    .renderArea(a -> a.extent().set(swapchain.width, swapchain.height));

            VkCommandBuffer[] cmdBuffers = createCommandBuffers(device, commandPool, swapchain.images.length);
            for (int i = 0; i < swapchain.images.length; i++) {
                renderPassBeginInfo.framebuffer(framebuffers[i]);
                VkCommandBuffer cmdBuffer = cmdBuffers[i];
                vkCmdBeginRenderPass(cmdBuffer, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);
                vkCmdEndRenderPass(cmdBuffer);
                _CHECK_(vkEndCommandBuffer(cmdBuffer), "Failed to end command buffer");
            }
            return cmdBuffers;
        }
    }

    private static VkCommandBuffer[] createCommandBuffers(VkDevice device, long pool, int count) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(count);
            _CHECK_(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.mallocStack(stack)
                    .commandBufferCount(count)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY), pCommandBuffers), "Failed to create command buffers");
            VkCommandBuffer[] cmdBuffers = new VkCommandBuffer[count];
            for (int i = 0; i < count; i++) {
                cmdBuffers[i] = new VkCommandBuffer(pCommandBuffers.get(i), device);
                _CHECK_(vkBeginCommandBuffer(cmdBuffers[i], VkCommandBufferBeginInfo.mallocStack(stack)), "Failed to begin command buffers!");
            }
            return cmdBuffers;
        }
    }

    private static void createSyncObjects(VkDevice device, SwapChain swapchain) {
        imageAcquireSemaphores = new long[swapchain.images.length];
        renderCompleteSemaphores = new long[swapchain.images.length];
        renderFences = new long[swapchain.images.length];

        for (int i = 0; i < swapchain.images.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSemaphore = stack.mallocLong(1);
                _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo.mallocStack(stack), null, pSemaphore),
                        "Failed to create image acquire semaphore");
                imageAcquireSemaphores[i] = pSemaphore.get(0);
                _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo.mallocStack(stack), null, pSemaphore),
                        "Failed to create raster semaphore");
                renderCompleteSemaphores[i] = pSemaphore.get(0);
                LongBuffer pFence = stack.mallocLong(1);
                _CHECK_(vkCreateFence(device, VkFenceCreateInfo.mallocStack(stack).flags(VK_FENCE_CREATE_SIGNALED_BIT), null,
                        pFence), "Failed to create fence");
                renderFences[i] = pFence.get(0);
            }
        }
    }

    private static void recreateOnResize() {
        swapChain = createSwapChain(device, surface, deviceAndQueueFamilies, swapChain);
        freeCommandBuffers();
        framebuffers = createFramebuffers(device, swapChain, renderPass, framebuffers);
        rasterCommandBuffers = createRasterCommandBuffers(device, swapChain, framebuffers, commandPool, renderPass);
    }

    private static boolean isExtensionEnabled(VkExtensionProperties.Buffer buf, String extension) {
        return buf.stream().anyMatch(p -> p.extensionNameString().equals(extension));
    }

    public static void _CHECK_(int vkRet, String msg) {
        if (vkRet != VK_SUCCESS) {
            throw new AssertionError(msg + " : " + translateVulkanResult(vkRet));
        }
    }

    public static String translateVulkanResult(int result) {
        switch (result) {
            // Success codes
            case VK_SUCCESS:
                return "Command successfully completed.";
            case VK_NOT_READY:
                return "A fence or query has not yet completed.";
            case VK_TIMEOUT:
                return "A wait operation has not completed in the specified time.";
            case VK_EVENT_SET:
                return "An event is signaled.";
            case VK_EVENT_RESET:
                return "An event is unsignaled.";
            case VK_INCOMPLETE:
                return "A return array was too small for the result.";
            case VK_SUBOPTIMAL_KHR:
                return "A swapchain no longer matches the surface properties exactly, but can still be used to present to the surface successfully.";

            // Error codes
            case VK_ERROR_OUT_OF_HOST_MEMORY:
                return "A host memory allocation has failed.";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY:
                return "A device memory allocation has failed.";
            case VK_ERROR_INITIALIZATION_FAILED:
                return "Initialization of an object could not be completed for implementation-specific reasons.";
            case VK_ERROR_DEVICE_LOST:
                return "The logical or physical device has been lost.";
            case VK_ERROR_MEMORY_MAP_FAILED:
                return "Mapping of a memory object has failed.";
            case VK_ERROR_LAYER_NOT_PRESENT:
                return "A requested layer is not present or could not be loaded.";
            case VK_ERROR_EXTENSION_NOT_PRESENT:
                return "A requested extension is not supported.";
            case VK_ERROR_FEATURE_NOT_PRESENT:
                return "A requested feature is not supported.";
            case VK_ERROR_INCOMPATIBLE_DRIVER:
                return "The requested version of Vulkan is not supported by the driver or is otherwise incompatible for implementation-specific reasons.";
            case VK_ERROR_TOO_MANY_OBJECTS:
                return "Too many objects of the type have already been created.";
            case VK_ERROR_FORMAT_NOT_SUPPORTED:
                return "A requested format is not supported on this device.";
            case VK_ERROR_SURFACE_LOST_KHR:
                return "A surface is no longer available.";
            case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR:
                return "The requested window is already connected to a VkSurfaceKHR, or to some other non-Vulkan API.";
            case VK_ERROR_OUT_OF_DATE_KHR:
                return "A surface has changed in such a way that it is no longer compatible with the swapchain, and further presentation requests using the "
                        + "swapchain will fail. Applications must query the new surface properties and recreate their swapchain if they wish to continue" + "presenting to the surface.";
            case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR:
                return "The display used by a swapchain does not use the same presentable image layout, or is incompatible in a way that prevents sharing an" + " image.";
            case VK_ERROR_VALIDATION_FAILED_EXT:
                return "A validation layer found an error.";
            default:
                return String.format("%s [%d]", "Unknown", Integer.valueOf(result));
        }
    }

    private static class SwapChain {
        long swapchain;
        long[] images;
        long[] imageViews;
        int width, height;
        ColorFormatAndSpace surfaceFormat;

        SwapChain(long swapchain, long[] images, long[] imageViews, int width, int height, ColorFormatAndSpace surfaceFormat) {
            this.swapchain = swapchain;
            this.images = images;
            this.imageViews = imageViews;
            this.width = width;
            this.height = height;
            this.surfaceFormat = surfaceFormat;
        }

        void free(VkDevice device) {
            vkDestroySwapchainKHR(device, swapchain, null);
            for (long imageView : imageViews)
                vkDestroyImageView(device, imageView, null);
        }
    }

    private static class ColorFormatAndSpace {
        int colorFormat;
        int colorSpace;

        ColorFormatAndSpace(int colorFormat, int colorSpace) {
            this.colorFormat = colorFormat;
            this.colorSpace = colorSpace;
        }
    }

    private static class QueueFamilies {
        List<Integer> graphicsFamilies = new ArrayList<>();
        List<Integer> presentFamilies = new ArrayList<>();

        int findSingleSuitableQueue() {
            return graphicsFamilies
                    .stream()
                    .filter(i -> presentFamilies.contains(i)).findAny().orElseThrow(
                            () -> new AssertionError("No suitable queue found"));
        }
    }

    private static class DeviceAndQueueFamilies {
        VkPhysicalDevice physicalDevice;
        QueueFamilies queuesFamilies;

        DeviceAndQueueFamilies(VkPhysicalDevice physicalDevice, QueueFamilies queuesFamilies) {
            this.physicalDevice = physicalDevice;
            this.queuesFamilies = queuesFamilies;
        }
    }

    private static class Vector2i {
        int x, y;

        Vector2i(int x, int y) {
            this.x = x;
            this.y = y;
        }

        void set(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
