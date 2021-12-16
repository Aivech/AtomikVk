package com.atomikmc.atomikvk.vulkan;

import com.atomikmc.atomikvk.AtomikVk;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.ImmutableIntArray;
import com.google.common.primitives.ImmutableLongArray;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static com.atomikmc.atomikvk.vulkan.Vulkan._CHECK_;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.system.MemoryStack.*;

class Swapchain {
    private final long pSwapchain;
    private final ImmutableLongArray images;
    private final int presentMode;
    private final VkSurfaceFormatKHR format;
    private final VkExtent2D extent;
    private final Details details;
    final ImmutableLongArray imageViews;


    Swapchain(long glfwWindow, PhysicalDevice gpu, VkDevice device, long vkSurface) {
        try(MemoryStack stack = stackPush()) {
            details = new Details(gpu.device, vkSurface, glfwWindow);
            format = details.chooseSurfaceFormat();
            presentMode = details.choosePresentMode(VK_PRESENT_MODE_MAILBOX_KHR);
            extent = details.extent;

            int imageCount = details.capabilities.minImageCount()+1;
            if (details.capabilities.maxImageCount() > 0 && imageCount > details.capabilities.maxImageCount()) {
                imageCount = details.capabilities.maxImageCount();
            }
            int queueCount = gpu.graphicsIndex != gpu.presentIndex ? 2 : 1;
            var queues = stack.mallocInt(queueCount).put(gpu.graphicsIndex);
            if(queueCount == 2) queues.put(gpu.presentIndex);
            queues.rewind();
            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(vkSurface)
                    .imageFormat(format.format())
                    .imageColorSpace(format.colorSpace())
                    .imageExtent(extent)
                    .minImageCount(imageCount)
                    .imageArrayLayers(1) // values >1 used for VR
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(gpu.graphicsIndex != gpu.presentIndex ? VK_SHARING_MODE_CONCURRENT : VK_SHARING_MODE_EXCLUSIVE)
                    .queueFamilyIndexCount(queueCount)
                    .pQueueFamilyIndices(queues)
                    .preTransform(details.capabilities.currentTransform()) // do nothing
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)     // window is not transparent
                    .presentMode(presentMode)
                    .clipped(true)        // do not draw under other windows
                    .oldSwapchain(VK_NULL_HANDLE); // holds an old swapchain if we are recreating due to resize or other event

            LongBuffer ppSwapchain = stack.mallocLong(1);
            _CHECK_(vkCreateSwapchainKHR(device, createInfo, null, ppSwapchain), "Failed to create swapchain!");
            pSwapchain = ppSwapchain.get(0);

            IntBuffer pImageCount = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(device, pSwapchain, pImageCount, null);
            LongBuffer ppVkImages = stack.mallocLong(pImageCount.get(0));
            vkGetSwapchainImagesKHR(device, pSwapchain, pImageCount, ppVkImages);

            ppVkImages.rewind();
            ImmutableLongArray.Builder temp = ImmutableLongArray.builder(pImageCount.get(0));
            while(ppVkImages.hasRemaining()) {
                temp.add(ppVkImages.get());
            }
            images = temp.build();

            ImmutableLongArray.Builder views = ImmutableLongArray.builder(images.length());
            for (long image: images.asList()) {
                try(MemoryStack stack2 = stackPush()) {
                    VkImageViewCreateInfo viewCreateInfo = VkImageViewCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                            .image(image)
                            .viewType(VK_IMAGE_TYPE_2D)
                            .format(format.format());
                    viewCreateInfo.components().set(VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY);
                    viewCreateInfo.subresourceRange().set(
                            VK_IMAGE_ASPECT_COLOR_BIT,
                            0,
                            1,
                            0,
                            1
                    );
                    LongBuffer ppImageView = stack.mallocLong(1);
                    _CHECK_(vkCreateImageView(device, viewCreateInfo, null, ppImageView), "failed to create image views!");
                    views.add(ppImageView.get(0));
                }
            }
            imageViews = views.build();
        }
    }

    long swapchain() {
        return pSwapchain;
    }

    int width() { return extent.width(); }
    int height() { return extent.height(); }

    VkExtent2D getExtent() { return extent; }

    int getImageFormat() { return this.format.format(); }

    void destroy(VkDevice device) {
        for(long imageView: imageViews.asList()) {
            vkDestroyImageView(device, imageView, null);
        }
        details.destroy();
        vkDestroySwapchainKHR(device, pSwapchain, null);
    }

    static private class Details {
        private VkSurfaceCapabilitiesKHR capabilities;
        private VkSurfaceFormatKHR.Buffer formats;
        private ImmutableIntArray presentModes;
        private VkExtent2D extent;

        private Details(VkPhysicalDevice gpu, long vkSurface, long glfwWindow) {
            try(MemoryStack stack = stackPush()) {
                capabilities = VkSurfaceCapabilitiesKHR.calloc();
                vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu, vkSurface, capabilities);

                IntBuffer pFormatCount = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, vkSurface, pFormatCount, null);
                formats = VkSurfaceFormatKHR.calloc(pFormatCount.get(0));
                pFormatCount.rewind();
                vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, vkSurface, pFormatCount, formats);

                IntBuffer pPresentModeCount = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, vkSurface, pPresentModeCount, null);
                IntBuffer pPresentModes = stack.mallocInt(pPresentModeCount.get(0));
                vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, vkSurface, pPresentModeCount.rewind(), pPresentModes);

                pPresentModes.rewind();
                ImmutableIntArray.Builder temp = ImmutableIntArray.builder(pPresentModeCount.get(0));
                while(pPresentModes.hasRemaining()) {
                    temp.add(pPresentModes.get());
                }
                presentModes = temp.build();

                if(capabilities.currentExtent().width() != -1) {
                    extent = VkExtent2D.calloc().set(capabilities.currentExtent().width(), capabilities.currentExtent().height());
                } else {
                    IntBuffer pWidth = stack.mallocInt(1);
                    IntBuffer pHeight = stack.mallocInt(1);
                    GLFW.glfwGetFramebufferSize(glfwWindow, pWidth, pHeight);
                    int width = Math.min(Math.max(capabilities.minImageExtent().width(), pWidth.get(0)), capabilities.maxImageExtent().width());
                    int height = Math.min(Math.max(capabilities.minImageExtent().height(), pHeight.get(0)), capabilities.maxImageExtent().height());
                    extent = VkExtent2D.calloc().set(width, height);
                }
            }
        }

        private VkSurfaceFormatKHR chooseSurfaceFormat() {
            for (VkSurfaceFormatKHR format : formats) {
                if (format.format() == VK_FORMAT_B8G8R8A8_SRGB && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    formats.rewind();
                    return format;
                }
            }
            AtomikVk.LOGGER.debug("DEBUG: preferred format not found");
            formats.rewind();
            return formats.get(0);
        }

        private int choosePresentMode(int preferredMode) {
            for(int mode: presentModes.asList()) {
                if(mode == preferredMode) return mode;
            }
            return VK_PRESENT_MODE_FIFO_KHR;
        }

        private void destroy() {
            if(capabilities != null) {
                capabilities.free();
                capabilities = null;
            }
            if(formats != null) {
                formats.free();
                formats = null;
            }
            if(extent != null) {
                extent.free();
                extent = null;
            }
            presentModes = null;
        }
    }
}
