package com.atomikmc.atomikvk.vulkan;

import com.atomikmc.atomikvk.common.resource.ShaderResource;
import com.atomikmc.atomikvk.shaderc.ShaderException;
import com.atomikmc.atomikvk.shaderc.SpirVCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.File;
import java.io.IOException;
import java.nio.LongBuffer;

import static com.atomikmc.atomikvk.vulkan.Vulkan._CHECK_;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK10.*;

public class Pipeline {
    private final RenderPass renderPass;
    private final long p_pipelineLayout;
    final long p_pipeline;

    Pipeline(VkDevice device, Swapchain swapchain, ShaderResource... shaderResources) {
        try(MemoryStack stack = stackPush()) {
            Shader[] shaders = new Shader[shaderResources.length];
            var p_stages = VkPipelineShaderStageCreateInfo.calloc(shaderResources.length, stack);
            for(int i = 0; i < shaders.length; i++) {
                shaders[i] = new Shader(device, shaderResources[i]);
                p_stages.put(shaders[i].stageCreateInfo);
            }
            p_stages.rewind();

            var vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(VkVertex.getBindDesc(stack))
                    .pVertexAttributeDescriptions(VkVertex.getAttrDesc(stack));

            var inputAssyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            var p_viewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y(0.0f)
                    .width((float)swapchain.width())
                    .height((float)swapchain.height())
                    .minDepth(0.0f).maxDepth(1.0f);

            var scissorOffset = VkOffset2D.calloc(stack).set(0,0);
            var p_scissor = VkRect2D.calloc(1, stack)
                    .offset(scissorOffset)
                    .extent(swapchain.getExtent());

            var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .pViewports(p_viewport)
                    .scissorCount(1)
                    .pScissors(p_scissor);

            var rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_BACK_BIT)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .depthBiasEnable(false);

            var multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            var p_colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);

            var colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(p_colorBlendAttachment);

            renderPass = new RenderPass(device, swapchain);

            var pipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

            var pp_pipelineLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(device, pipelineLayoutCreateInfo, null, pp_pipelineLayout), "Failed to create pipeline layout");
            p_pipelineLayout = pp_pipelineLayout.get(0);

            var p_createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(p_stages)
                    .pVertexInputState(vertexInputState)
                    .pInputAssemblyState(inputAssyState)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pColorBlendState(colorBlend)
                    .layout(p_pipelineLayout)
                    .renderPass(renderPass.vkRenderPass)
                    .subpass(0);

            LongBuffer pp_pipeline = stack.mallocLong(1);
            _CHECK_(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, p_createInfo, null, pp_pipeline), "Failed to create graphics pipeline.");
            p_pipeline = pp_pipeline.get(0);

            for(int i = 0; i < shaders.length; i++) {
                shaders[i].free(device);
            }
        }
    }

    long getRenderPass() { return renderPass.vkRenderPass; }

    void destroy(VkDevice device) {
        vkDestroyPipeline(device, p_pipeline, null);
        vkDestroyPipelineLayout(device, p_pipelineLayout, null);
        renderPass.free(device);
    }

    private class Shader {
        private final ShaderResource shader;
        private final long pVkShaderModule;
        private final VkPipelineShaderStageCreateInfo stageCreateInfo;

        private Shader (VkDevice device, ShaderResource shader) {
            try(MemoryStack stack = stackPush()) {
                this.shader = shader;
                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(shader.getBytes());
                LongBuffer ppShaderModule = stack.mallocLong(1);
                _CHECK_(vkCreateShaderModule(device, createInfo, null, ppShaderModule), "Failed to create shader module!");
                pVkShaderModule = ppShaderModule.get(0);

                stageCreateInfo = VkPipelineShaderStageCreateInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(shader.type.vkStageFlag)
                        .module(pVkShaderModule)
                        .pName(MemoryUtil.memUTF8(SpirVCompiler.GLSL_ENTRY_POINT));
            }
        }

        public void free(VkDevice device) {
            MemoryUtil.memFree(stageCreateInfo.pName());
            stageCreateInfo.free();
            vkDestroyShaderModule(device, pVkShaderModule, null);
        }
    }

    private class RenderPass{
        private final long vkRenderPass;

        RenderPass(VkDevice device, Swapchain swapchain) {
            try(MemoryStack stack = stackPush()) {
                var p_colorAttach = VkAttachmentDescription.calloc(1,stack)
                        .format(swapchain.getImageFormat())
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .finalLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

                var p_colorAttachRef = VkAttachmentReference.calloc(1, stack)
                        .attachment(0)
                        .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

                var p_subpass = VkSubpassDescription.calloc(1, stack)
                        .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                        .colorAttachmentCount(1)
                        .pColorAttachments(p_colorAttachRef);

                var dependency = VkSubpassDependency.calloc(1, stack)
                        .srcSubpass(VK_SUBPASS_EXTERNAL)
                        .dstSubpass(0)
                        .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .srcAccessMask(0)
                        .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);


                var createInfo = VkRenderPassCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                        .pAttachments(p_colorAttach)
                        .pSubpasses(p_subpass)
                        .pDependencies(dependency);

                var p_renderPass = stack.mallocLong(1);
                _CHECK_(vkCreateRenderPass(device, createInfo, null, p_renderPass), "Failed to create render pass.");
                vkRenderPass = p_renderPass.get(0);
            }
        }

        private void free(VkDevice device) {
            vkDestroyRenderPass(device, vkRenderPass, null );
        }
    }

}
