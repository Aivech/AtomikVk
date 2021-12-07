package com.atomikmc.atomikvk.shaderc;

import org.lwjgl.util.shaderc.ShadercIncludeResolveI;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.util.shaderc.Shaderc.*;

public class SpirVCompiler implements AutoCloseable {
    private static final String ENTRY_POINT = "main";

    private final long shaderc_compiler;
    private final long shaderc_compile_options;

    public SpirVCompiler() {
        shaderc_compiler = shaderc_compiler_initialize();
        shaderc_compile_options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_optimization_level(shaderc_compile_options, shaderc_optimization_level_performance);
        shaderc_compile_options_set_target_env(shaderc_compile_options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
    }

    @Override
    public void close() {
        shaderc_compile_options_release(shaderc_compile_options);
        shaderc_compiler_release(shaderc_compiler);
    }



    public SpirVResult compile(File inputFile) throws IOException {
        String glsl = Files.readString(inputFile.toPath());
        File parentDir = inputFile.getParentFile();
        IncludeResolver includeResolver = new IncludeResolver(parentDir);
        shaderc_compile_options_set_include_callbacks(this.shaderc_compile_options, includeResolver, new IncludeResolver.Releaser(),0);

        long spirV = shaderc_compile_into_spv(shaderc_compiler, glsl, getShaderType(inputFile.getName()), inputFile.getName(), ENTRY_POINT, shaderc_compile_options);
        checkResult(spirV, shaderc_result_get_compilation_status(spirV));

        return new SpirVResult(spirV);
    }

    private static int getShaderType(String fileName) {
        if(fileName.endsWith(".frag")) return shaderc_glsl_fragment_shader;
        if(fileName.endsWith(".vert")) return shaderc_glsl_vertex_shader;
        if(fileName.endsWith(".comp")) return shaderc_glsl_compute_shader;
        throw new ShaderException("Unknown shader type for file \""+fileName+"\"");
    }

    private static void checkResult(long compile_result, int status_code) {
        if (status_code == shaderc_compilation_status_success) return;
        String msg = shaderc_result_get_error_message(compile_result);
        shaderc_result_release(compile_result);
        throw new ShaderException("ShaderC compile failed: error "+ status_code + " - " + msg);
    }

    public static class SpirVResult implements AutoCloseable {
        public final long result;
        private SpirVResult(long result) {
            this.result = result;
        }

        @Override
        public void close() {
            shaderc_result_release(result);
        }
    }
}
