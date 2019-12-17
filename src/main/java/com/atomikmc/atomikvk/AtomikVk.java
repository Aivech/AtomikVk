package com.atomikmc.atomikvk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

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

        VkInstanceCreateInfo vkInstanceCreateInfo = VkInstanceCreateInfo.create();

        VkApplicationInfo vkAppInfo = vkInstanceCreateInfo.pApplicationInfo();

        PointerBuffer vkInstanceBuffer = PointerBuffer.allocateDirect(1);

        if (VK10.vkCreateInstance(vkInstanceCreateInfo, null, vkInstanceBuffer) != VK10.VK_SUCCESS) {
            GLFW.glfwTerminate();
            throw new AssertionError("Failed to create VkInstance!");
        }

        System.out.println(vkInstanceBuffer.get(0));

        VkInstance vkInstance = new VkInstance(vkInstanceBuffer.get(0), vkInstanceCreateInfo);

        LongBuffer surfaceBuffer = LongBuffer.allocate(1);


        if (GLFWVulkan.glfwCreateWindowSurface(vkInstance, window, null, surfaceBuffer) != VK10.VK_SUCCESS) {
            GLFW.glfwTerminate();
            throw new AssertionError("Failed to create Vulkan surface from window!");
        }

        while (!GLFW.glfwWindowShouldClose(window)) {

            GLFW.glfwPollEvents();
        }


        GLFW.glfwTerminate();
    }
}
