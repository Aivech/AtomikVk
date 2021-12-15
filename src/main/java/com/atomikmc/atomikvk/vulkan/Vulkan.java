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
    public static final boolean ENABLE_VALIDATION = true;
    // public static final int MAX_FRAMES_IN_FLIGHT = 2;

    private long glfwWindow;
    private VkInstance instance;
    private long vkDebugUtilsMessenger;
    private long surfaceKHR;
    private VkPhysicalDevice gpu;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private VkQueue presentationQueue;
    private ShaderResource[] shaders;
    private Swapchain swapchain;
    private Pipeline pipeline;
    private long[] framebuffers;
    private long commandPool;
    private long vertexBuffer;
    private long vertexBufMem;
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
        chooseGPU();
        createLogicalDevice();
        getQueues();
        loadInitialResources();
        swapchain = new com.atomikmc.atomikvk.vulkan.Swapchain(window, gpu, device, surfaceKHR,
                QueueFamilies.graphicsFamily.getAsInt(), QueueFamilies.presentationFamily.getAsInt());
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

        swapchain = new Swapchain(glfwWindow, gpu, device, surfaceKHR,
                QueueFamilies.graphicsFamily.getAsInt(), QueueFamilies.presentationFamily.getAsInt());
        pipeline = new Pipeline(device, swapchain, shaders);
        createFramebuffers();
        createCommandPool();
        createCommandBuffers();
        createSyncObjects();
    }

    public void destroySwapchain() {
        for(int i = 0; i < framebuffers.length; i++) {
            vkDestroySemaphore(device, imageAvailableSemaphore[i], null);
            vkDestroySemaphore(device, renderFinishedSemaphore[i], null);
            vkDestroyFence(device, fences[i], null);
        }
        vkDestroyCommandPool(device, commandPool, null);
        for (long framebuffer : framebuffers) {
            vkDestroyFramebuffer(device, framebuffer, null);
        }
        pipeline.destroy(device);
        swapchain.destroy(device);
    }

    @Override
    public void cleanup() {
        vkDeviceWaitIdle(device);

        destroySwapchain();

        vkDestroyBuffer(device, vertexBuffer, null);
        vkFreeMemory(device, vertexBufMem, null);

        for(var shader: shaders) {
            shader.close();
        }

        if (device != null) vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surfaceKHR, null);
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, vkDebugUtilsMessenger, null);
        vkDestroyInstance(instance, null);
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
            // and enable them
            PointerBuffer pVkRequiredLayers = stack.mallocPointer(validationLayers.length);
            for (CharSequence layer : validationLayers) {
                pVkRequiredLayers.put(stack.UTF8(layer, true));
            }
            pVkRequiredLayers.rewind();  // necessary or nothing gets loaded
            pCreateInfo.ppEnabledLayerNames(pVkRequiredLayers);

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

    private void chooseGPU() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pPhysDeviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pPhysDeviceCount, null);
            int physDeviceCount = pPhysDeviceCount.get(0);
            if (physDeviceCount == 0) throw (new RuntimeException("No Vulkan-compatible devices!"));

            PointerBuffer pPhysicalDevices = stack.mallocPointer(physDeviceCount);
            vkEnumeratePhysicalDevices(instance, pPhysDeviceCount, pPhysicalDevices);
            ArrayList<VkPhysicalDevice> devices = new ArrayList<>();
            for (int i = 0; i < physDeviceCount; i++) {
                devices.add(new VkPhysicalDevice(pPhysicalDevices.get(i), instance));
            }

            int score = 0;
            for (VkPhysicalDevice device : devices) {
                int deviceScore = evaluateDevice(device);
                if (deviceScore > score) {
                    score = deviceScore;
                    gpu = device;
                }
            }

            if (gpu == null) {
                throw new RuntimeException("Failed to find a suitable GPU!");
            }

        }
    }

    private void createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);

            findQueueFamilies(gpu);
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
            VkDeviceQueueCreateInfo graphicsQueueInfo = queueCreateInfos.get(0);
            graphicsQueueInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(QueueFamilies.graphicsFamily.getAsInt()) // always present, we checked earlier
                    .pQueuePriorities(stack.floats(1.0f));

            queueCreateInfos.put(0, graphicsQueueInfo);
            queueCreateInfos.rewind();

            PointerBuffer ppDeviceExtensionNames = stack.mallocPointer(deviceRequiredExtensions.length);
            for(CharSequence str : deviceRequiredExtensions) {
                ppDeviceExtensionNames.put(stack.UTF8(str, true));
            }
            ppDeviceExtensionNames.rewind();

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.pQueueCreateInfos(queueCreateInfos)
                    .pEnabledFeatures(features)
                    .ppEnabledExtensionNames(ppDeviceExtensionNames);

            PointerBuffer pVkDevice = stack.mallocPointer(1);
            _CHECK_(vkCreateDevice(gpu, createInfo, null, pVkDevice), "Failed to create logical device!");
            device = new VkDevice(pVkDevice.get(0), gpu, createInfo);
        }
    }

    private void getQueues() {
        // TODO: don't make duplicate queues if the presentation and graphics queue are the same
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Both optionals have already been checked
            PointerBuffer pGraphQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, QueueFamilies.graphicsFamily.getAsInt(), 0, pGraphQueue);
            graphicsQueue = new VkQueue(pGraphQueue.get(0), device);

            PointerBuffer pPresentQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, QueueFamilies.presentationFamily.getAsInt(),0,pPresentQueue);
            presentationQueue = new VkQueue(pPresentQueue.get(0),device);
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
                    .queueFamilyIndex(QueueFamilies.graphicsFamily.getAsInt());
            var pp_commandPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, createInfo, null, pp_commandPool), "Failed to create command pool.");
            commandPool = pp_commandPool.get(0);
        }
    }

    private void createVertexBuffer() {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(VkVertex.SIZE * VkVertex.VERTICES.length)
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var p_buffer = stack.mallocLong(1);
            _CHECK_(vkCreateBuffer(device, bufferInfo, null, p_buffer), "Failed to create vertex buffer.");
            vertexBuffer = p_buffer.get(0);

            var bufMemRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, vertexBuffer, bufMemRequirements);

            var bufAllocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(bufMemRequirements.size())
                    .memoryTypeIndex(findMemoryType(bufMemRequirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer p_vertexBufMem = stack.mallocLong(1);
            _CHECK_(vkAllocateMemory(device, bufAllocInfo, null, p_vertexBufMem), "Failed to allocate memory for vertex buffer.");
            vertexBufMem = p_vertexBufMem.get(0);
            vkBindBufferMemory(device, vertexBuffer, vertexBufMem, 0);

            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(device, vertexBufMem, 0, bufferInfo.size(), 0, data);
            memCopy(data, bufferInfo.size());
            vkUnmapMemory(device, vertexBufMem);
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
                vkCmdBindVertexBuffers(commandBuffers[i], 0, new long[]{vertexBuffer}, new long[]{0});
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

    private int evaluateDevice(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int score = 1;

            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device, properties);
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(device, features);

            // get device queue families
            findQueueFamilies(device);

            // check extension support
            boolean requiredExtensions = checkDeviceExtensionSupport(device);

            // mandatory features
            if (!features.geometryShader() || !QueueFamilies.isComplete() || !requiredExtensions ) {
                return -1; // a score of -1 will never be chosen, indicating an unsuitable device
            }
            if(!Swapchain.verifyDeviceSupport(device, surfaceKHR)) return -1;

            // optional features
            if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
                score += 100;


            // TODO: add actual requirements - for now, nearly anything will do

            AtomikVk.LOGGER.debug("Found candidate device " + properties.deviceNameString() + " with score = " + score);
            return score;
        }
    }

    // this entire thing is a mess
    private void findQueueFamilies(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pQueueFamilyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, null);
            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(pQueueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, queueFamilies);

            for (int i = 0; i < pQueueFamilyCount.get(0); i++) {
                VkQueueFamilyProperties family = queueFamilies.get();
                if ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    QueueFamilies.graphicsFamily = OptionalInt.of(i);
                }

                IntBuffer presentSupport = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceSupportKHR(device,i,surfaceKHR,presentSupport);
                if(presentSupport.get(0) == 1) {
                    QueueFamilies.presentationFamily = OptionalInt.of(i);
                }
                if(QueueFamilies.isComplete()) break;
            }
        }
    }

    private boolean checkDeviceExtensionSupport(VkPhysicalDevice device) {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pExtensionCount = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, pExtensionCount, null);

            VkExtensionProperties.Buffer pDeviceExtensions = VkExtensionProperties.calloc(pExtensionCount.get(), stack);
            vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, pExtensionCount.rewind(), pDeviceExtensions);

            HashSet<CharSequence> requiredExtensions = new HashSet<>(Arrays.asList(deviceRequiredExtensions));

            for (VkExtensionProperties extension : pDeviceExtensions) {
                requiredExtensions.remove(extension.extensionNameString());
            }

            return requiredExtensions.isEmpty();
        }
    }

    private int findMemoryType(int filter, int propertyFlags) {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            var memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(gpu, memProperties);
            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((filter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & propertyFlags) != 0) return i;
            }
        }
        throw new RuntimeException("Failed to find suitable memory type!");
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

    // java structs when
    private static class QueueFamilies {
        private static OptionalInt graphicsFamily = OptionalInt.empty();
        private static OptionalInt presentationFamily = OptionalInt.empty();

        private static boolean isComplete() {
            return graphicsFamily.isPresent() && presentationFamily.isPresent();
        }
    }
}