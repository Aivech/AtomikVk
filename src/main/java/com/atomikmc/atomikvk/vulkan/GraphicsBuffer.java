package com.atomikmc.atomikvk.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static com.atomikmc.atomikvk.vulkan.Vulkan._CHECK_;
import static org.lwjgl.vulkan.VK10.*;

class GraphicsBuffer {
    final long backingMemory;
    final long buffer;

    GraphicsBuffer(PhysicalDevice gpu, VkDevice device, long size, int vkUsageFlags, int vkShareMode, int propertyFlags) {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(vkUsageFlags)
                    .sharingMode(vkShareMode);
            var p_buffer = stack.mallocLong(1);
            _CHECK_(vkCreateBuffer(device, bufferInfo, null, p_buffer), "Failed to create vertex buffer.");
            buffer = p_buffer.get(0);

            var bufMemRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, bufMemRequirements);

            var bufAllocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(bufMemRequirements.size())
                    .memoryTypeIndex(findMemoryType(gpu, bufMemRequirements.memoryTypeBits(), propertyFlags));
            LongBuffer p_vertexBufMem = stack.mallocLong(1);
            _CHECK_(vkAllocateMemory(device, bufAllocInfo, null, p_vertexBufMem), "Failed to allocate memory for vertex buffer.");
            backingMemory = p_vertexBufMem.get(0);
            vkBindBufferMemory(device, buffer, backingMemory, 0);
        }
    }

    public void free(VkDevice device) {
        if(buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer, null);
        if(backingMemory != VK_NULL_HANDLE) vkFreeMemory(device, backingMemory, null);
    }

    private static int findMemoryType(PhysicalDevice gpu, int filter, int propertyFlags) {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            var memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(gpu.device, memProperties);
            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((filter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & propertyFlags) != 0) return i;
            }
        }
        throw new RuntimeException("Failed to find suitable memory type!");
    }
}
