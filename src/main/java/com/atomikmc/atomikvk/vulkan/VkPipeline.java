package com.atomikmc.atomikvk.vulkan;

import com.atomikmc.atomikvk.shaderc.ShaderException;
import com.atomikmc.atomikvk.shaderc.SpirVCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.File;
import java.io.IOException;
import java.nio.LongBuffer;

import static com.atomikmc.atomikvk.vulkan.Vulkan._CHECK_;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK10.*;

public class VkPipeline {

    VkPipeline(VkDevice device, SpirVCompiler compiler, File... shaderFiles) {
        try(MemoryStack stack = stackPush()) {
            Shader[] shaders = new Shader[shaderFiles.length];
            for(int i = 0; i < shaders.length; i++) {
                shaders[i] = new Shader(device, compiler, shaderFiles[i]);
            }


            for(int i = 0; i < shaders.length; i++) {
                shaders[i].free(device);
            }
        }
    }

    private class Shader {
        private final SpirVCompiler.Result spirv;
        private final long pVkShaderModule;
        private final VkPipelineShaderStageCreateInfo stageCreateInfo;

        private Shader (VkDevice device, SpirVCompiler compiler, File glsl) {
            try(MemoryStack stack = stackPush()) {
                spirv = compiler.compile(glsl);

                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(spirv.bytes());
                LongBuffer ppShaderModule = stack.mallocLong(1);
                _CHECK_(vkCreateShaderModule(device, createInfo, null, ppShaderModule), "Failed to create shader module!");
                pVkShaderModule = ppShaderModule.get(0);

                stageCreateInfo = VkPipelineShaderStageCreateInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(spirv.type.vkStageFlag)
                        .module(pVkShaderModule)
                        .pName(MemoryUtil.memUTF8(glsl.getName()));

            } catch (IOException e) {
                throw new ShaderException("Failed to compile GLSL.", e);
            }
        }

        public void free(VkDevice device) {
            MemoryUtil.memFree(stageCreateInfo.pName());
            stageCreateInfo.free();
            vkDestroyShaderModule(device, pVkShaderModule, null);
            spirv.close();
        }
    }
}
