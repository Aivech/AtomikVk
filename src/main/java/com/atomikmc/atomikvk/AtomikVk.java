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
            if (vkCreateInstance(vkInstanceCreateInfo, null, vkInstanceBuffer) != VK_SUCCESS) {
                throw new AssertionError("Failed to create VkInstance!");
            }

            VkInstance vkInstance = new VkInstance(vkInstanceBuffer.get(0), vkInstanceCreateInfo);
            LongBuffer surfaceBuffer = stack.mallocLong(1);

            if (glfwCreateWindowSurface(vkInstance, window, null, surfaceBuffer) != VK_SUCCESS) {
                throw new AssertionError("Failed to create Vulkan surface from window!");
            }

            IntBuffer physicalDeviceCount = stack.mallocInt(1);
            if (vkEnumeratePhysicalDevices(vkInstance, physicalDeviceCount, null) != VK_SUCCESS) {
                throw new AssertionError("No physical devices!");
            }

            System.out.println(physicalDeviceCount.get(0));

            while (!glfwWindowShouldClose(window)) {

                glfwPollEvents();
            }
        } finally {
            glfwTerminate();
        }
    }
}
