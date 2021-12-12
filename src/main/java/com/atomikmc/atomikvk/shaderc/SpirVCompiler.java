package com.atomikmc.atomikvk.shaderc;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

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



    public Result compile(File inputFile) throws IOException {
        String glsl = Files.readString(inputFile.toPath());
        File parentDir = inputFile.getParentFile();
        IncludeResolver includeResolver = new IncludeResolver(parentDir);
        ShaderType type = getShaderType(inputFile.getName());
        shaderc_compile_options_set_include_callbacks(this.shaderc_compile_options, includeResolver, new IncludeResolver.Releaser(),0);

        long spirV = shaderc_compile_into_spv(shaderc_compiler, glsl, type.shadercGlslType, inputFile.getName(), ENTRY_POINT, shaderc_compile_options);
        checkResult(spirV, shaderc_result_get_compilation_status(spirV));

        return new Result(spirV, type);
    }

    private static ShaderType getShaderType(String fileName) {
        if(fileName.endsWith(".frag")) return ShaderType.FRAG;
        if(fileName.endsWith(".vert")) return ShaderType.VERT;
        if(fileName.endsWith(".comp")) return ShaderType.COMP;
        throw new ShaderException("Unknown shader type for file \""+fileName+"\"");
    }

    private static void checkResult(long compile_result, int status_code) {
        if (status_code == shaderc_compilation_status_success) return;
        String msg = shaderc_result_get_error_message(compile_result);
        shaderc_result_release(compile_result);
        throw new ShaderException("ShaderC compile failed: error "+ status_code + " - " + msg);
    }

    public static class Result implements AutoCloseable {
        private final long result;
        public final ShaderType type;
        private Result(long result, ShaderType type) {
            this.result = result;
            this.type = type;
        }

        public ByteBuffer bytes() {
            return shaderc_result_get_bytes(result);
        }

        @Override
        public void close() {
            shaderc_result_release(result);
        }
    }
}
