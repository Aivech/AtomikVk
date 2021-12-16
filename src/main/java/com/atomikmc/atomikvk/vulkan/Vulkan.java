package com.atomikmc.atomikvk.vulkan;

import com.atomikmc.atomikvk.AtomikVk;
import com.atomikmc.atomikvk.common.GraphicsProvider;
import com.atomikmc.atomikvk.common.resource.ShaderResource;
import com.atomikmc.atomikvk.shaderc.ShaderException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.OptionalInt;

import static org.lwjgl.glfw.GLFW.glfwWaitEvents;
import static org.lwjgl.vulkan.EXTDebugReport.VK_ERROR_VALIDATION_FAILED_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;


public class Vulkan implements GraphicsProvider {
    public static final CharSequence[] validationLayers = {"VK_LAYER_KHRONOS_validation"};
    public static final CharSequence[] debugExtensions = {"VK_EXT_debug_utils"};
    public static final CharSequence[] deviceRequiredExtensions = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
    public static final boolean ENABLE_VALIDATION = true; //true;
    // public static final int MAX_FRAMES_IN_FLIGHT = 2;

    private long glfwWindow;
    private VkInstance instance;
    private long vkDebugUtilsMessenger;
    private long surfaceKHR;
    private PhysicalDevice gpu;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private VkQueue presentationQueue;
    private VkQueue transferQueue;
    private ShaderResource[] shaders;
    private Swapchain swapchain;
    private Pipeline pipeline;
    private long[] framebuffers;
    private long commandPool;
    private GraphicsBuffer vertexBuffer;
    private VkCommandBuffer[] commandBuffers;
    private long[] imageAvailableSemaphore;
    private long[] renderFinishedSemaphore;
    private long[] fences;
    private long[] imagesInFlight;
    private final int[] currentFrame = new int[1];
    private int frameCounter = 0;

    @Override
    public void init(long window) {
        glfwWindow = window;
        createInstance();
        setupDebugMessenger();
        createSurface(window);
        gpu = PhysicalDevice.selectVkPhysDevice(instance, surfaceKHR, deviceRequiredExtensions);
        createLogicalDevice();
        getQueues();
        loadInitialResources();
        swapchain = new com.atomikmc.atomikvk.vulkan.Swapchain(window, gpu, device, surfaceKHR);
        pipeline = new Pipeline(device, swapchain, shaders);
        createFramebuffers();
        createCommandPool();
        createVertexBuffer();
        createCommandBuffers();
        createSyncObjects();
    }
    @Override
    public void drawFrame() {
        vkWaitForFences(device, fences[frameCounter], true, -1);
        var result = vkAcquireNextImageKHR(device, swapchain.swapchain(), -1, imageAvailableSemaphore[frameCounter], VK_NULL_HANDLE, currentFrame);
        if (result == VK_ERROR_OUT_OF_DATE_KHR) {
            windowResizeUpdate();
            return;
        }

        if(imagesInFlight[currentFrame[0]] != VK_NULL_HANDLE) {
            vkWaitForFences(device, imagesInFlight[currentFrame[0]], true, -1);
        }
        imagesInFlight[currentFrame[0]] = fences[frameCounter];
        try(MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer waitSemaphore = stack.mallocLong(1).put(imageAvailableSemaphore[frameCounter]).rewind();
            LongBuffer signalSemaphore = stack.mallocLong(1).put(renderFinishedSemaphore[frameCounter]).rewind();
            IntBuffer waitStages = stack.mallocInt(1).put(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).rewind();
            var frameSubmitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(waitSemaphore)
                    .pWaitDstStageMask(waitStages)
                    .pSignalSemaphores(signalSemaphore)
                    .pCommandBuffers(stack.mallocPointer(1).put(commandBuffers[currentFrame[0]].address()).rewind());

            vkResetFences(device, fences[frameCounter]);
            _CHECK_(vkQueueSubmit(graphicsQueue,  frameSubmitInfo, fences[frameCounter]), "failed to submit draw command buffer");
            var presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(signalSemaphore.rewind())
                    .swapchainCount(1)
                    .pSwapchains(stack.mallocLong(1).put(swapchain.swapchain()).rewind())
                    .pImageIndices(stack.mallocInt(1).put(currentFrame[0]).rewind());

            var presentResult = vkQueuePresentKHR(presentationQueue, presentInfo);
            if(presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) windowResizeUpdate();
            else _CHECK_(presentResult, "Failed to present image!");
            frameCounter = (frameCounter + 1) % framebuffers.length;
            // vkDeviceWaitIdle(device);
        }
    }

    @Override
    public void windowResizeUpdate() {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(glfwWindow, width, height);
        while(width[0] == 0 || height[0] == 0) {
            glfwWaitEvents();
            glfwGetFramebufferSize(glfwWindow, width, height);
        }

        vkDeviceWaitIdle(device);

        destroySwapchain();

        swapchain = new Swapchain(glfwWindow, gpu, device, surfaceKHR);
        pipeline = new Pipeline(device, swapchain, shaders);
        createFramebuffers();
        createCommandPool();
        createCommandBuffers();
        createSyncObjects();
    }

    public void destroySwapchain() {
        if(framebuffers != null)
            for(int i = 0; i < framebuffers.length; i++) {
                vkDestroySemaphore(device, imageAvailableSemaphore[i], null);
                vkDestroySemaphore(device, renderFinishedSemaphore[i], null);
                vkDestroyFence(device, fences[i], null);
            }
        if(commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
        if(framebuffers != null)
            for (long framebuffer : framebuffers) {
                vkDestroyFramebuffer(device, framebuffer, null);
            }
        if (pipeline != null) pipeline.destroy(device);
        if (swapchain != null) swapchain.destroy(device);
    }

    @Override
    public void cleanup() {
        if(device != null) {
            vkDeviceWaitIdle(device);

            destroySwapchain();

            if(vertexBuffer != null) vertexBuffer.free(device);

            for(var shader: shaders) {
                shader.close();
            }

            vkDestroyDevice(device, null);
        }

        if(surfaceKHR != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surfaceKHR, null);

        if(ENABLE_VALIDATION && vkDebugUtilsMessenger != VK_NULL_HANDLE)
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, vkDebugUtilsMessenger, null);

        if(instance != null) vkDestroyInstance(instance, null);
    }

    private void createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo pAppInfo = VkApplicationInfo.calloc(stack);
            pAppInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    // .pApplicationName("AtomikVk")
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    // .pEngineName("No Engine")
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK_API_VERSION_1_0);

            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo.calloc(stack);
            pCreateInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            pCreateInfo.pApplicationInfo(pAppInfo);

            // get available extensions
            IntBuffer pPropertyCount = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((CharSequence) null, pPropertyCount, null);
            VkExtensionProperties.Buffer extensions = VkExtensionProperties.calloc(pPropertyCount.get(0), stack);
            vkEnumerateInstanceExtensionProperties((CharSequence) null, pPropertyCount, extensions);
            for (VkExtensionProperties ext : extensions) {
                AtomikVk.LOGGER.debug(ext.extensionNameString());
            }

            // get required VK extensions
            PointerBuffer pGLFWVkRequiredExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (ENABLE_VALIDATION) {
                PointerBuffer pVkRequiredExtensions = stack.mallocPointer(
                        (pGLFWVkRequiredExtensions != null ? pGLFWVkRequiredExtensions.capacity() : 0) + debugExtensions.length);
                if (pGLFWVkRequiredExtensions != null) {
                    for (int i = 0; i < pGLFWVkRequiredExtensions.capacity(); i++) {
                        pVkRequiredExtensions.put(pGLFWVkRequiredExtensions.get(i));
                    }
                }
                for (CharSequence str : debugExtensions) {
                    pVkRequiredExtensions.put(stack.UTF8(str, true));
                }
                pVkRequiredExtensions.rewind(); // necessary or nothing gets loaded

                pCreateInfo.ppEnabledExtensionNames(pVkRequiredExtensions);
            } else {
                pCreateInfo.ppEnabledExtensionNames(pGLFWVkRequiredExtensions);
            }

            // check for validation layers
            if (ENABLE_VALIDATION && !checkValidationLayerSupport()) {
                throw new AssertionError("Requested validation layers not present!");
            }

            if(ENABLE_VALIDATION) {
                // and enable them
                PointerBuffer pVkRequiredLayers = stack.mallocPointer(validationLayers.length);
                for (CharSequence layer : validationLayers) {
                    pVkRequiredLayers.put(stack.UTF8(layer, true));
                }
                pVkRequiredLayers.rewind();  // necessary or nothing gets loaded
                pCreateInfo.ppEnabledLayerNames(pVkRequiredLayers);
            }

            // create VkInstance
            PointerBuffer vkInstanceBuffer = stack.callocPointer(1);
            _CHECK_(vkCreateInstance(pCreateInfo, null, vkInstanceBuffer), "Failed to create VkInstance!");
            instance = new VkInstance(vkInstanceBuffer.get(0), pCreateInfo);
        }
    }

    private void setupDebugMessenger() {
        if (!ENABLE_VALIDATION) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT messengerCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            messengerCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                    .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                    .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT)
                    .pfnUserCallback(Vulkan::VkDebugMessengerCallback);

            LongBuffer pVkDebugUtilsMessenger = stack.callocLong(1);
            _CHECK_(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, messengerCreateInfo, null, pVkDebugUtilsMessenger), "Failed to set up debug messenger!");
            vkDebugUtilsMessenger = pVkDebugUtilsMessenger.get(0);
        }
    }

    private void createSurface(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            _CHECK_(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, pSurface), "Failed to create window surface!");
            surfaceKHR = pSurface.get(0);
        }

    }

    private void createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            int queueCount = gpu.getQueueCount();
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(queueCount, stack);
            VkDeviceQueueCreateInfo graphicsQueueInfo = queueCreateInfos.rewind().get();
            graphicsQueueInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(gpu.graphicsIndex)
                    .pQueuePriorities(stack.floats(1.0f));

            if(gpu.presentIndex != gpu.graphicsIndex) {
                var presentQueueInfo = queueCreateInfos.get();
                presentQueueInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(gpu.presentIndex)
                        .pQueuePriorities(stack.floats(1.0f));

            }
            if(gpu.transferIndex != gpu.graphicsIndex) {
                var transferQueueInfo = queueCreateInfos.get();
                transferQueueInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(gpu.transferIndex)
                        .pQueuePriorities(stack.floats(1.0f));
            }
            queueCreateInfos.rewind();

            PointerBuffer ppDeviceExtensionNames = stack.mallocPointer(deviceRequiredExtensions.length);
            for(CharSequence str : deviceRequiredExtensions) {
                ppDeviceExtensionNames.put(stack.UTF8(str, true));
            }
            ppDeviceExtensionNames.rewind();

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfos)
                    .pEnabledFeatures(features)
                    .ppEnabledExtensionNames(ppDeviceExtensionNames);

            PointerBuffer pVkDevice = stack.mallocPointer(1);
            _CHECK_(vkCreateDevice(gpu.device, createInfo, null, pVkDevice), "Failed to create logical device!");
            device = new VkDevice(pVkDevice.get(0), gpu.device, createInfo);
        }
    }

    private void getQueues() {
        // TODO: don't make duplicate queues if the presentation and graphics queue are the same
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Both optionals have already been checked
            PointerBuffer pGraphQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, gpu.graphicsIndex, 0, pGraphQueue);
            graphicsQueue = new VkQueue(pGraphQueue.get(0), device);

            if(gpu.presentIndex != gpu.graphicsIndex) {
                PointerBuffer pPresentQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(device, gpu.presentIndex,0,pPresentQueue);
                presentationQueue = new VkQueue(pPresentQueue.get(0),device);
            } else presentationQueue = graphicsQueue;

            if(gpu.transferIndex != gpu.graphicsIndex) {
                PointerBuffer pTransferQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(device, gpu.presentIndex,0,pTransferQueue);
                transferQueue = new VkQueue(pTransferQueue.get(0),device);
            } else transferQueue = graphicsQueue;
        }
    }

    private void loadInitialResources() {
        shaders = new ShaderResource[2];
        ClassLoader loader = AtomikVk.class.getClassLoader();
        try {
            shaders[0] = new ShaderResource(new File(loader.getResource("shader/triangle.vert").toURI()));
            shaders[1] = new ShaderResource(new File(loader.getResource("shader/triangle.frag").toURI()));
        } catch (URISyntaxException e) {
            throw new ShaderException("Failed to load shader resource", e);
        }

    }

    private void createFramebuffers() {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            framebuffers = new long[swapchain.imageViews.length()];
            for(int i = 0; i < framebuffers.length; i++) {
                LongBuffer imageView = stack.mallocLong(1).put(swapchain.imageViews.get(i)).rewind();
                var framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(pipeline.getRenderPass())
                        .attachmentCount(1)
                        .pAttachments(imageView)
                        .width(swapchain.width())
                        .height(swapchain.height())
                        .layers(1);

                LongBuffer pp_framebuffer = stack.mallocLong(1);
                _CHECK_(vkCreateFramebuffer(device, framebufferInfo, null, pp_framebuffer),"Failed to create framebuffer on index " + i);
                framebuffers[i] = pp_framebuffer.get(0);
            }
        }
    }

    private void createCommandPool() {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            var createInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(gpu.graphicsIndex);
            var pp_commandPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, createInfo, null, pp_commandPool), "Failed to create command pool.");
            commandPool = pp_commandPool.get(0);
        }
    }

    private void createVertexBuffer() {
        var size = VkVertex.SIZE * VkVertex.VERTICES.length;
        vertexBuffer = new GraphicsBuffer(gpu, device, size,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_SHARING_MODE_EXCLUSIVE,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                );
        try(MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(device, vertexBuffer.backingMemory, 0, size, 0, data);
            memCopy(data, size);
            vkUnmapMemory(device, vertexBuffer.backingMemory);
        }
    }

    private void createCommandBuffers() {
        commandBuffers = new VkCommandBuffer[framebuffers.length];
        try(MemoryStack stack = MemoryStack.stackPush()) {
            var allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(commandBuffers.length);
            var p_buffers = stack.mallocPointer(commandBuffers.length);
            _CHECK_(vkAllocateCommandBuffers(device, allocInfo, p_buffers), "Failed to allocate command buffers.");
            for(int i = 0; i < commandBuffers.length; i++) {
                commandBuffers[i] = new VkCommandBuffer(p_buffers.get(i), device);

                var beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

                _CHECK_(vkBeginCommandBuffer(commandBuffers[i], beginInfo), "Failed to begin recording command buffer at index " + i);

                var renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(pipeline.getRenderPass())
                        .framebuffer(framebuffers[i]);
                renderPassInfo.renderArea().offset().set(0,0);
                renderPassInfo.renderArea().extent(swapchain.getExtent());

                var clearColor = VkClearValue.calloc(1, stack);
                clearColor.color().float32(0,0f)
                        .float32(1,0f)
                        .float32(2,0f)
                        .float32(3,1f);

                renderPassInfo.clearValueCount(1)
                        .pClearValues(clearColor);

                vkCmdBeginRenderPass(commandBuffers[i], renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
                vkCmdBindPipeline(commandBuffers[i], VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.p_pipeline);
                vkCmdBindVertexBuffers(commandBuffers[i], 0, new long[]{vertexBuffer.buffer}, new long[]{0});
                vkCmdDraw(commandBuffers[i], VkVertex.VERTICES.length,1,0,0);
                vkCmdEndRenderPass(commandBuffers[i]);
                _CHECK_(vkEndCommandBuffer(commandBuffers[i]), "Failed to record command buffer at index "+i);
            }
        }
    }

    private void createSyncObjects() {
        imageAvailableSemaphore = new long[framebuffers.length];
        renderFinishedSemaphore = new long[framebuffers.length];
        fences = new long[framebuffers.length];
        imagesInFlight = new long[framebuffers.length];
        Arrays.fill(imagesInFlight, VK_NULL_HANDLE);
        try(MemoryStack stack = MemoryStack.stackPush()) {
            var semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            var fenceCreateInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pointer = stack.mallocLong(1);
            for(int i = 0; i < framebuffers.length; i++) {
                _CHECK_(vkCreateSemaphore(device, semaphoreCreateInfo, null, pointer.rewind()), "Failed to create semaphores.");
                imageAvailableSemaphore[i] = pointer.get(0);
                _CHECK_(vkCreateSemaphore(device, semaphoreCreateInfo, null, pointer.rewind()), "Failed to create semaphores.");
                renderFinishedSemaphore[i] = pointer.get(0);
                _CHECK_(vkCreateFence(device, fenceCreateInfo, null, pointer.rewind()), "Failed to create fence.");
                fences[i] = pointer.get(0);
            }
        }
    }

    public static int VkDebugMessengerCallback(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
        VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        AtomikVk.LOGGER.error("VK DEBUG: " + data.pMessageString());

        return VK_FALSE;
    }

    private boolean checkValidationLayerSupport() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // get layer count
            IntBuffer pLayerCount = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(pLayerCount, null);

            VkLayerProperties.Buffer pVkLayerProperties = VkLayerProperties.calloc(pLayerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(pLayerCount, pVkLayerProperties);

            AtomikVk.LOGGER.debug("VK validation layers available:");
            for (VkLayerProperties properties : pVkLayerProperties) {
                AtomikVk.LOGGER.debug(properties.layerNameString());
            }

            int count = 0;
            for (CharSequence layerName : validationLayers) {
                for (VkLayerProperties prop : pVkLayerProperties) {
                    if (prop.layerNameString().contentEquals(layerName)) {
                        count++;
                        break;
                    }
                }
            }
            return count == validationLayers.length;
        }
    }

    private void memCopy(PointerBuffer dest, long size) {
        var buffer = dest.getByteBuffer(0, (int) size);
        for(var v : VkVertex.VERTICES) {
            buffer.putFloat(v.pos.x());
            buffer.putFloat(v.pos.y());

            buffer.putFloat(v.color.x());
            buffer.putFloat(v.color.y());
            buffer.putFloat(v.color.z());
        }
    }

    public static void _CHECK_(int vkRet, String msg) {
        if (vkRet != VK_SUCCESS) {
            throw new RuntimeException(msg + " : " + translateVulkanResult(vkRet));
        }
    }

    public static String translateVulkanResult(int result) {
        return switch (result) {
            // Success codes
            case VK_SUCCESS -> "Command successfully completed.";
            case VK_NOT_READY -> "A fence or query has not yet completed.";
            case VK_TIMEOUT -> "A wait operation has not completed in the specified time.";
            case VK_EVENT_SET -> "An event is signaled.";
            case VK_EVENT_RESET -> "An event is unsignaled.";
            case VK_INCOMPLETE -> "A return array was too small for the result.";
            case VK_SUBOPTIMAL_KHR -> "A swapchain no longer matches the surface properties exactly, but can still be used to present to the surface successfully.";

            // Error codes
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "A host memory allocation has failed.";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "A device memory allocation has failed.";
            case VK_ERROR_INITIALIZATION_FAILED -> "Initialization of an object could not be completed for implementation-specific reasons.";
            case VK_ERROR_DEVICE_LOST -> "The logical or physical device has been lost.";
            case VK_ERROR_MEMORY_MAP_FAILED -> "Mapping of a memory object has failed.";
            case VK_ERROR_LAYER_NOT_PRESENT -> "A requested layer is not present or could not be loaded.";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "A requested extension is not supported.";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "A requested feature is not supported.";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "The requested version of Vulkan is not supported by the driver or is otherwise incompatible for implementation-specific reasons.";
            case VK_ERROR_TOO_MANY_OBJECTS -> "Too many objects of the type have already been created.";
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> "A requested format is not supported on this device.";
            case VK_ERROR_SURFACE_LOST_KHR -> "A surface is no longer available.";
            case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR -> "The requested window is already connected to a VkSurfaceKHR, or to some other non-Vulkan API.";
            case VK_ERROR_OUT_OF_DATE_KHR -> "A surface has changed in such a way that it is no longer compatible with the swapchain, and further presentation requests using the "
                    + "swapchain will fail. Applications must query the new surface properties and recreate their swapchain if they wish to continue" + "presenting to the surface.";
            case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR -> "The display used by a swapchain does not use the same presentable image layout, or is incompatible in a way that prevents sharing an" + " image.";
            case VK_ERROR_VALIDATION_FAILED_EXT -> "A validation layer found an error.";
            default -> String.format("%s [%d]", "Unknown", result);
        };
    }
}