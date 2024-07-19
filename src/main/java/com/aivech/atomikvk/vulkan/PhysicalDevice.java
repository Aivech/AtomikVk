package com.aivech.atomikvk.vulkan;

import com.aivech.atomikvk.AtomikVk;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;

import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;

final class PhysicalDevice {
    final VkPhysicalDevice device;
    final int graphicsIndex;
    final int presentIndex;
    final int computeIndex;
    final int transferIndex;

    private PhysicalDevice(VkPhysicalDevice device, long khrSurface) {
        this.device = device;

        // get queue families
        try (MemoryStack stack = stackPush()) {
            IntBuffer pQueueFamilyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, null);
            var queueFamilyProperties = VkQueueFamilyProperties.calloc(pQueueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, queueFamilyProperties);

            int graphicsIndex = -1;
            int presentIndex = -1;
            int computeIndex = -1;
            int transferIndex = -1;
            for (int i = 0; queueFamilyProperties.hasRemaining(); i++) {
                if (graphicsIndex != -1 && presentIndex != -1 && transferIndex != -1) break;

                // graphics queue
                var family = queueFamilyProperties.get();
                if ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && graphicsIndex == -1) {
                    graphicsIndex = i;
                    continue;
                }

                // presentation queue
                var presentSupport = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, khrSurface, presentSupport);
                if (presentSupport.get(0) == VK_TRUE && presentIndex == -1) {
                    presentIndex = i;
                    continue;
                }

                // todo: compute queue

                // transfer queue
                if ((family.queueFlags() & VK_QUEUE_TRANSFER_BIT) == VK_QUEUE_TRANSFER_BIT && (family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0)
                    transferIndex = i;
            }
            // no dedicated present queue, use the graphics queue or the transfer queue
            if (presentIndex == -1) {
                var presentSupport = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceSupportKHR(device, graphicsIndex, khrSurface, presentSupport);
                presentIndex = presentSupport.get(0) == VK_TRUE ? graphicsIndex : transferIndex;
            }
            // no dedicated transfer queue, use the graphics queue
            if (transferIndex == -1) transferIndex = graphicsIndex;
            this.graphicsIndex = graphicsIndex;
            this.computeIndex = -1; // todo: compute queue
            this.presentIndex = presentIndex;
            this.transferIndex = transferIndex;
        }
    }

    int getQueueCount() {
        int count = 1;
        if (presentIndex != graphicsIndex) count++;
        if (transferIndex != graphicsIndex) count++;
        return count;
    }

    static PhysicalDevice selectVkPhysDevice(VkInstance instance, long khrSurface, CharSequence[] requiredExtensionNames) {
        try (MemoryStack stack = stackPush()) {
            // count all compatible devices
            IntBuffer pDeviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pDeviceCount, null);

            // quit if no vulkan devices
            // TODO: fallback to openGL?
            int deviceCount = pDeviceCount.get(0);
            if (deviceCount == 0) throw new RuntimeException("No Vulkan-compatible devices on this system.");

            // list compatible devices
            PointerBuffer pDevices = stack.mallocPointer(deviceCount);
            vkEnumeratePhysicalDevices(instance, pDeviceCount, pDevices);

            // select a device
            VkPhysicalDevice selection = null;
            int score = Integer.MIN_VALUE;

            while (pDevices.hasRemaining()) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(pDevices.get(), instance);
                int candidateScore = scoreDevice(candidate, khrSurface, requiredExtensionNames);
                if (candidateScore > score) selection = candidate;
                AtomikVk.LOGGER.debug("Received score " + candidateScore);
            }

            // return a PhysicalDevice containing the selected device
            if (selection != null) return new PhysicalDevice(selection, khrSurface);
                // no device with required features
                // TODO: attempt OpenGL fallback
            else throw new RuntimeException("No suitable Vulkan device found.");
        }
    }

    private static int scoreDevice(VkPhysicalDevice device, long khrSurface, CharSequence[] requiredExtensionNames) {
        try (MemoryStack stack = stackPush()) {
            int score = 0;
            // get device properties and features
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceProperties(device, properties);
            vkGetPhysicalDeviceFeatures(device, features);
            AtomikVk.LOGGER.debug("Evaluating device ID: " + properties.deviceID() + " name: " + properties.deviceName());

            // check for required properties and features
            if (!features.geometryShader()) return Integer.MIN_VALUE; // missing required properties or features

            // check device for required queue families
            boolean graphicsFlag = false;
            boolean presentFlag = false;
            IntBuffer pQueueFamilyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, null);
            var queueFamilyProperties = VkQueueFamilyProperties.calloc(pQueueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, queueFamilyProperties);
            for (int i = 0; queueFamilyProperties.hasRemaining(); i++) {
                var family = queueFamilyProperties.get();
                if ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) graphicsFlag = true;

                var presentSupport = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, khrSurface, presentSupport);
                if (presentSupport.get(0) == VK_TRUE) presentFlag = true;

                // TODO: move to debug
                AtomikVk.LOGGER.error("Queue Family: " + i + " graphics: " + ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) +
                        " compute: " + ((family.queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) +
                        " transfer: " + ((family.queueFlags() & VK_QUEUE_TRANSFER_BIT) != 0) +
                        " present: " + (presentSupport.get(0) == 1));
            }
            if (!graphicsFlag && !presentFlag) return Integer.MIN_VALUE; // missing required queues

            // check device for required extensions
            var extensionCount = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, extensionCount, null);
            var extensions = VkExtensionProperties.calloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, extensionCount, extensions);

            var requiredExtensions = new HashSet<>(Arrays.asList(requiredExtensionNames));
            for (var extension : extensions) {
                requiredExtensions.remove(extension.extensionNameString());
            }
            if (!requiredExtensions.isEmpty()) return Integer.MIN_VALUE; // missing required extensions

            // check for required surface formats and present modes
            IntBuffer pFormatCount = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, khrSurface, pFormatCount, null);
            IntBuffer pPresentModeCount = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, khrSurface, pPresentModeCount, null);
            if (pFormatCount.get(0) == 0 || pPresentModeCount.get(0) == 0)
                return Integer.MIN_VALUE; // missing required formats or modes

            // score based on optional features
            if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score += 100;
            return score;
        }
    }


}
