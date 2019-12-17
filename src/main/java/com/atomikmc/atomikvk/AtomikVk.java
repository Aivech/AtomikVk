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

import java.nio.IntBuffer;
import java.nio.LongBuffer;

public class AtomikVk {

    public static Logger logger = LogManager.getLogger("AtomikVk");

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

        long window = glfwCreateWindow(640, 480, "AtomikVk", 0, 0);

        if (window == 0) {
            glfwTerminate();
            throw new AssertionError("No window!");
        }

        try (MemoryStack stack = stackPush()) {

            PointerBuffer extensions = stack.mallocPointer(vkRequiredExtensions.remaining() + 1);
            extensions.put(vkRequiredExtensions);
            extensions.put(stack.UTF8(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME));
            extensions.flip();

            VkInstanceCreateInfo vkInstanceCreateInfo = VkInstanceCreateInfo.create()
                    .ppEnabledExtensionNames(extensions)
                    .pApplicationInfo(VkApplicationInfo.mallocStack(stack).apiVersion(VK_API_VERSION_1_1));


            PointerBuffer vkInstanceBuffer = stack.mallocPointer(1);
            _CHECK_(vkCreateInstance(vkInstanceCreateInfo, null, vkInstanceBuffer), "Failed to create VkInstance!");

            VkInstance vkInstance = new VkInstance(vkInstanceBuffer.get(0), vkInstanceCreateInfo);
            LongBuffer surfaceBuffer = stack.mallocLong(1);

            _CHECK_(glfwCreateWindowSurface(vkInstance, window, null, surfaceBuffer), "Failed to create Vulkan surface from window!");

            IntBuffer pPhysicalDeviceCount = stack.mallocInt(1);
            int physDeviceCount = pPhysicalDeviceCount.get(0);
            PointerBuffer pPhysDevices = stack.mallocPointer(physDeviceCount);
            _CHECK_(vkEnumeratePhysicalDevices(vkInstance, pPhysicalDeviceCount, pPhysDevices), "Could not get physical devices!");

            for (int i = 0; i < physDeviceCount; i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(pPhysDevices.get(i), vkInstance);
            }


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
}
