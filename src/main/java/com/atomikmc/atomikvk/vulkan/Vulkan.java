package com.atomikmc.atomikvk.vulkan;

import com.atomikmc.atomikvk.AtomikVk;
import com.atomikmc.atomikvk.common.GraphicsProvider;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Queue;

import static org.lwjgl.vulkan.EXTDebugReport.VK_ERROR_VALIDATION_FAILED_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;


public class Vulkan implements GraphicsProvider {
    public static final CharSequence[] validationLayers = {"VK_LAYER_KHRONOS_validation"};
    public static final CharSequence[] debugExtensions = {"VK_EXT_debug_utils"};
    public static final boolean ENABLE_VALIDATION = true;

    private VkInstance instance;
    private long vkDebugUtilsMessenger;
    private VkPhysicalDevice gpu;

    @Override
    public void init() {
        createInstance();
        setupDebugMessenger();
        chooseGPU();
    }

    @Override
    public void update(IntBuffer pImageIndex, int w, int h) {

    }

    @Override
    public void cleanup() {
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, vkDebugUtilsMessenger, null);
        vkDestroyInstance(instance, null);
    }

    private void createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo pAppInfo = VkApplicationInfo.callocStack(stack);
            pAppInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    // .pApplicationName("AtomikVk")
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    // .pEngineName("No Engine")
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK_API_VERSION_1_0);

            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo.callocStack(stack);
            pCreateInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            pCreateInfo.pApplicationInfo(pAppInfo);

            // get available extensions
            IntBuffer pPropertyCount = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((CharSequence) null, pPropertyCount, null);
            VkExtensionProperties.Buffer extensions = VkExtensionProperties.mallocStack(pPropertyCount.get(0), stack);
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
            VkDebugUtilsMessengerCreateInfoEXT messengerCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack);
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

    private void chooseGPU() {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pPhysDeviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance,pPhysDeviceCount,null);
            int physDeviceCount = pPhysDeviceCount.get(0);
            if (physDeviceCount == 0) throw(new RuntimeException("No Vulkan-compatible devices!"));

            PointerBuffer pPhysicalDevices = stack.mallocPointer(physDeviceCount);
            vkEnumeratePhysicalDevices(instance, pPhysDeviceCount, pPhysicalDevices);
            ArrayList<VkPhysicalDevice> devices = new ArrayList<>();
            for(int i = 0; i < physDeviceCount; i++) {
                devices.add(new VkPhysicalDevice(pPhysicalDevices.get(i), instance));
            }

            int score = 0;
            for(VkPhysicalDevice device : devices) {
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

            VkLayerProperties.Buffer pVkLayerProperties = VkLayerProperties.callocStack(pLayerCount.get(0), stack);
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
        try(MemoryStack stack = MemoryStack.stackPush()) {
            int score = 1;

            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.callocStack(stack);
            vkGetPhysicalDeviceProperties(device, properties);
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.callocStack(stack);
            vkGetPhysicalDeviceFeatures(device, features);

            // get device queue families
            findQueueFamilies(device);

            // mandatory features
            if(!features.geometryShader() || QueueFamilies.graphicsFamily == null) {
                return -1; // a score of -1 will never be chosen, indicating an unsuitable device
            }

            // optional features
            if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
                score += 100;


            // TODO: add actual requirements - for now, nearly anything will do

            AtomikVk.LOGGER.debug("Found candidate device "+properties.deviceNameString()+" with score = "+score);
            return score;
        }
    }

    private void findQueueFamilies(VkPhysicalDevice device) {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pQueueFamilyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device,pQueueFamilyCount,null);
            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.callocStack(pQueueFamilyCount.get(0),stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device,pQueueFamilyCount,queueFamilies);

            for(VkQueueFamilyProperties family : queueFamilies) {
                if((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    QueueFamilies.graphicsFamily = family;
                }
            }
        }
    }

    public static void _CHECK_(int vkRet, String msg) {
        if (vkRet != VK_SUCCESS) {
            throw new AssertionError(msg + " : " + translateVulkanResult(vkRet));
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
    private class QueueFamilies {
        private static VkQueueFamilyProperties graphicsFamily;

    }
}
