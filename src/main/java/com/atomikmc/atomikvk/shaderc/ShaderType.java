package com.atomikmc.atomikvk.shaderc;

import org.lwjgl.util.shaderc.Shaderc;
import static org.lwjgl.vulkan.VK10.*;

public enum ShaderType {

    VERT(Shaderc.shaderc_glsl_vertex_shader, VK_SHADER_STAGE_VERTEX_BIT),
    FRAG(Shaderc.shaderc_glsl_fragment_shader, VK_SHADER_STAGE_FRAGMENT_BIT),
    COMP(Shaderc.shaderc_glsl_compute_shader, VK_SHADER_STAGE_COMPUTE_BIT);

    public final int shadercGlslType;
    public final int vkStageFlag;

    ShaderType(int shadercGlslType, int vkStageFlag) {
        this.shadercGlslType = shadercGlslType;
        this.vkStageFlag = vkStageFlag;
    }
}
