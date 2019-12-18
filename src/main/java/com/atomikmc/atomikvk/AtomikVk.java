package com.atomikmc.atomikvk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


import static java.lang.Math.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugReport.*;
import static org.lwjgl.vulkan.KHRDedicatedAllocation.*;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK11.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

public class AtomikVk {

    public static Logger logger = LogManager.getLogger("AtomikVk");

    private static VkDevice device = null;
    private static long surface = 0;
    private static DeviceAndQueueFamilies deviceAndQueueFamilies = null;
    private static Swapchain swapchain = null;

    public static final int INITIAL_WINDOW_WIDTH = 640;
    public static final int INITIAL_WINDOW_HEIGHT = 480;

    public static void main(String[] args) {

        if (!glfwInit()) {
            throw new AssertionError("GLFW init failed");
        }

        glfwSetErrorCallback((error, description) -> {
            logger.error("GLFW ERROR: " + error);
            logger.error(GLFWErrorCallback.getDescription(description));
        });

        if (!glfwVulkanSupported()) {
            glfwTerminate();
            throw new AssertionError("No Vulkan");
        }

        PointerBuffer vkRequiredExtensions = glfwGetRequiredInstanceExtensions();
        if (vkRequiredExtensions == null) {
            glfwTerminate();
            throw new AssertionError("Missing list of required Vulkan extensions");
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        long window = glfwCreateWindow(INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_WIDTH, "AtomikVk", 0, 0);

        if (window == 0) {
            glfwTerminate();
            throw new AssertionError("No window!");
        }

        try (MemoryStack stack = stackPush()) {

            PointerBuffer pExtensions = stack.mallocPointer(vkRequiredExtensions.remaining() + 1);
            pExtensions.put(vkRequiredExtensions);
            pExtensions.put(stack.UTF8(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME));
            pExtensions.flip();

            VkInstanceCreateInfo vkInstanceCreateInfo = VkInstanceCreateInfo.create()
                    .ppEnabledExtensionNames(pExtensions)
                    .pApplicationInfo(VkApplicationInfo.mallocStack(stack).apiVersion(VK_API_VERSION_1_1));


            PointerBuffer vkInstanceBuffer = stack.mallocPointer(1);
            _CHECK_(vkCreateInstance(vkInstanceCreateInfo, null, vkInstanceBuffer), "Failed to create VkInstance!");

            VkInstance vkInstance = new VkInstance(vkInstanceBuffer.get(0), vkInstanceCreateInfo);
            LongBuffer pSurface = stack.mallocLong(1);

            _CHECK_(glfwCreateWindowSurface(vkInstance, window, null, pSurface), "Failed to create Vulkan surface from window!");
            surface = pSurface.get(0);

            IntBuffer pPhysicalDeviceCount = stack.mallocInt(1);
            _CHECK_(vkEnumeratePhysicalDevices(vkInstance, pPhysicalDeviceCount, null), "Could not get physical devices!");
            int physDeviceCount = pPhysicalDeviceCount.get(0);
            PointerBuffer pPhysDevices = stack.mallocPointer(physDeviceCount);
            _CHECK_(vkEnumeratePhysicalDevices(vkInstance, pPhysicalDeviceCount, pPhysDevices), "Could not get physical devices!");

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
                        deviceAndQueueFamilies = new DeviceAndQueueFamilies(device, queuesFamilies);
                        break;
                    }
                }
            }

            if (deviceAndQueueFamilies == null) {
                throw new AssertionError("No GPU Detected");
            }

            int queueFamily = deviceAndQueueFamilies.queuesFamilies.findSingleSuitableQueue();



            try (MemoryStack stack1 = stackPush()) {
                IntBuffer pPropertyCount = stack1.mallocInt(1);
                _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount, null),
                        "Failed to enumerate device extensions");
                VkExtensionProperties.Buffer pProperties = VkExtensionProperties.mallocStack(pPropertyCount.get(0), stack1);
                _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount,
                        pProperties),
                        "Failed to enumerate device extensions");

                if (!isExtensionEnabled(pProperties, VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                    throw new AssertionError("Missing required extension: " + VK_KHR_SWAPCHAIN_EXTENSION_NAME);
                if (!isExtensionEnabled(pProperties, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME))
                    throw new AssertionError("Missing required extension: " + VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);

                PointerBuffer extensions = stack1.mallocPointer(2 + 1);
                extensions.put(stack1.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                        .put(stack1.UTF8(VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME));
                if (isExtensionEnabled(pProperties, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME)) {
                    extensions.put(stack1.UTF8(VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME));
                }

                VkDeviceCreateInfo pCreateInfo = VkDeviceCreateInfo.mallocStack(stack1)
                        .pQueueCreateInfos(VkDeviceQueueCreateInfo.mallocStack(1, stack1)
                                .queueFamilyIndex(queueFamily)
                                .pQueuePriorities(stack1.floats(1.0f)))
                        .ppEnabledExtensionNames(extensions.flip());

                PointerBuffer pDevice = stack1.mallocPointer(1);
                _CHECK_(vkCreateDevice(deviceAndQueueFamilies.physicalDevice, pCreateInfo, null, pDevice), "Failed to create device");

                device = new VkDevice(pDevice.get(0), deviceAndQueueFamilies.physicalDevice, pCreateInfo);
            }

            VkQueue queue = null;
            try (MemoryStack stack1 = stackPush()) {
                PointerBuffer pQueue = stack1.mallocPointer(1);
                vkGetDeviceQueue(device, queueFamily, 0, pQueue);
                queue = new VkQueue(pQueue.get(0), device);
            }

            Swapchain swapchain = createSwapChain();

            while (!glfwWindowShouldClose(window)) {

                glfwPollEvents();
            }
        } finally {
            glfwTerminate();
        }
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

    private static boolean isExtensionEnabled(VkExtensionProperties.Buffer buf, String extension) {
        return buf.stream().anyMatch(p -> p.extensionNameString().equals(extension));
    }

    private static class Swapchain {
        long swapchain;
        long[] images;
        long[] imageViews;
        int width, height;
        ColorFormatAndSpace surfaceFormat;

        Swapchain(long swapchain, long[] images, long[] imageViews, int width, int height, ColorFormatAndSpace surfaceFormat) {
            this.swapchain = swapchain;
            this.images = images;
            this.imageViews = imageViews;
            this.width = width;
            this.height = height;
            this.surfaceFormat = surfaceFormat;
        }

        void free() {
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

    private static Swapchain createSwapChain() {
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
                    .oldSwapchain(swapchain != null ? swapchain.swapchain : VK_NULL_HANDLE)
                    .clipped(true)
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);

            LongBuffer pSwapChain = stack.mallocLong(1);
            _CHECK_(vkCreateSwapchainKHR(device, pCreateInfo, null, pSwapChain), "Failed to create swap chain");
            if (swapchain != null) {
                swapchain.free();
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
            return new Swapchain(pSwapChain.get(0), images, imageViews, swapchainExtents.x, swapchainExtents.y, surfaceFormat);

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
