package com.atomikmc.atomikvk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryStack.*;

import java.nio.LongBuffer;

public class AtomikVk {

    public static Logger logger = LogManager.getLogger("AtomikVk");

    public static void main(String[] args) {

        if (!GLFW.glfwInit()) {
            throw new AssertionError("GLFW init failed");
        }

        GLFW.glfwSetErrorCallback((error, description) -> {
            logger.error("GLFW ERROR: " + error);
            logger.error(GLFWErrorCallback.getDescription(description));
        });

        if (!GLFWVulkan.glfwVulkanSupported()) {
            GLFW.glfwTerminate();
            throw new AssertionError("No Vulkan");
        }

        PointerBuffer vkRequiredExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (vkRequiredExtensions == null) {
            GLFW.glfwTerminate();
            throw new AssertionError("Missing list of required Vulkan extensions");
        }

        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);

        long window = GLFW.glfwCreateWindow(640, 480, "AtomikVk", 0, 0);

        if (window == 0) {
            GLFW.glfwTerminate();
            throw new AssertionError("No window!");
        }

        try (MemoryStack stack = stackPush()) {

            PointerBuffer extensions = stack.mallocPointer(vkRequiredExtensions.remaining() + 1);
            extensions.put(vkRequiredExtensions);
            extensions.put(stack.UTF8(KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME));
            extensions.flip();

            VkInstanceCreateInfo vkInstanceCreateInfo = VkInstanceCreateInfo.create()
                    .ppEnabledExtensionNames(extensions)
                    .pApplicationInfo(VkApplicationInfo.mallocStack(stack).apiVersion(VK11.VK_API_VERSION_1_1));


            PointerBuffer vkInstanceBuffer = stack.mallocPointer(1);
            if (VK11.vkCreateInstance(vkInstanceCreateInfo, null, vkInstanceBuffer) != VK10.VK_SUCCESS) {
                GLFW.glfwTerminate();
                throw new AssertionError("Failed to create VkInstance!");
            }

            VkInstance vkInstance = new VkInstance(vkInstanceBuffer.get(0), vkInstanceCreateInfo);
            LongBuffer surfaceBuffer = stack.mallocLong(1);

            if (GLFWVulkan.glfwCreateWindowSurface(vkInstance, window, null, surfaceBuffer) != VK10.VK_SUCCESS) {
                GLFW.glfwTerminate();
                throw new AssertionError("Failed to create Vulkan surface from window!");
            }
            while (!GLFW.glfwWindowShouldClose(window)) {

                GLFW.glfwPollEvents();
            }
        }


        GLFW.glfwTerminate();
    }
}
