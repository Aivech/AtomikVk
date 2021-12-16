package com.atomikmc.atomikvk.vulkan;

import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.*;

public record VkVertex(Vector2fc pos, Vector3fc color) {
    static final int SIZE = (2 + 3) * Float.BYTES;
    private static final int OFFSET_POS = 0;
    private static final int OFFSET_COLOR = 2 * Float.BYTES;

    static VkVertexInputBindingDescription.Buffer getBindDesc(MemoryStack stack) {
        return VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0)
                .stride(VkVertex.SIZE)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
    }

    static VkVertexInputAttributeDescription.Buffer getAttrDesc(MemoryStack stack) {
        var p_attrDesc = VkVertexInputAttributeDescription.calloc(2, stack);
        p_attrDesc.get(0)
                .binding(0)
                .location(0)
                .format(VK_FORMAT_R32G32_SFLOAT)
                .offset(OFFSET_POS);
        p_attrDesc.get(1)
                .binding(0)
                .location(1)
                .format(VK_FORMAT_R32G32B32_SFLOAT)
                .offset(OFFSET_COLOR);
        return p_attrDesc.rewind();
    }

    static final VkVertex[] VERTICES = {
            new VkVertex(new Vector2f(0.0f, 0.25f), new Vector3f(0.0f, 0.0f, 1.0f)),
            new VkVertex(new Vector2f(-0.25f, -0.25f), new Vector3f(1.0f, 0.0f, 0.0f)),
            new VkVertex(new Vector2f(0.25f, -0.25f), new Vector3f(0.0f, 1.0f, 0.0f)),

            new VkVertex(new Vector2f(0.0f, -0.25f + .625f), new Vector3f(0.0f, 0.0f, 1.0f)),
            new VkVertex(new Vector2f(0.25f, 0.25f + .625f), new Vector3f(0.0f, 1.0f, 1.0f)),
            new VkVertex(new Vector2f(-0.25f, 0.25f + .625f), new Vector3f(1.0f, 0.0f, 1.0f)),

            new VkVertex(new Vector2f(0.0f, -0.25f - .625f), new Vector3f(1.0f, 1.0f, 0.0f)),
            new VkVertex(new Vector2f(0.25f, 0.25f - .625f), new Vector3f(0.0f, 1.0f, 0.0f)),
            new VkVertex(new Vector2f(-0.25f, 0.25f - .625f), new Vector3f(1.0f, 0.0f, 0.0f)),

            new VkVertex(new Vector2f(0.0f - .625f, -0.25f + .5f), new Vector3f(1.0f, 1.0f, 0.0f)),
            new VkVertex(new Vector2f(0.25f - .625f, 0.25f + .5f), new Vector3f(1.0f, 0.0f, 1.0f)),
            new VkVertex(new Vector2f(-0.25f - .625f, 0.25f + .5f), new Vector3f(0.0f, 1.0f, 1.0f)),

            new VkVertex(new Vector2f(0.0f + .625f, -0.25f + .5f), new Vector3f(1.0f, 1.0f, 0.0f)),
            new VkVertex(new Vector2f(0.25f + .625f, 0.25f + .5f), new Vector3f(1.0f, 0.0f, 1.0f)),
            new VkVertex(new Vector2f(-0.25f + .625f, 0.25f + .5f), new Vector3f(0.0f, 1.0f, 1.0f)),

            new VkVertex(new Vector2f(0.0f - .625f, -0.25f - .5f), new Vector3f(1.0f, 1.0f, 0.0f)),
            new VkVertex(new Vector2f(0.25f - .625f, 0.25f - .5f), new Vector3f(1.0f, 0.0f, 1.0f)),
            new VkVertex(new Vector2f(-0.25f - .625f, 0.25f - .5f), new Vector3f(0.0f, 1.0f, 1.0f)),

            new VkVertex(new Vector2f(0.0f + .625f, -0.25f - .5f), new Vector3f(1.0f, 1.0f, 0.0f)),
            new VkVertex(new Vector2f(0.25f + .625f, 0.25f - .5f), new Vector3f(1.0f, 0.0f, 1.0f)),
            new VkVertex(new Vector2f(-0.25f + .625f, 0.25f - .5f), new Vector3f(0.0f, 1.0f, 1.0f)),

    };
}
