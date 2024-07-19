package com.aivech.atomikvk.shaderc;

import com.aivech.atomikvk.common.resource.ShaderType;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

import static org.lwjgl.util.shaderc.Shaderc.*;

public class SpirVCompiler {
    public static final String GLSL_ENTRY_POINT = "main";
    private static SpirVCompiler instance;

    private final long shaderc_compiler;
    private final long shaderc_compile_options;

    public static void init() {
        if (instance != null) destroy();
        instance = new SpirVCompiler();
    }

    public static void destroy() {
        shaderc_compile_options_release(instance.shaderc_compile_options);
        shaderc_compiler_release(instance.shaderc_compiler);
        instance = null;
    }

    private SpirVCompiler() {
        shaderc_compiler = shaderc_compiler_initialize();
        shaderc_compile_options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_optimization_level(shaderc_compile_options, shaderc_optimization_level_performance);
        shaderc_compile_options_set_target_env(shaderc_compile_options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
    }

    public static Result compile(File inputFile, ShaderType type) throws IOException {
        if (instance == null) throw new RuntimeException("SPIR-V Compiler must be initialized!");

        String glsl = Files.readString(inputFile.toPath());
        File parentDir = inputFile.getParentFile();
        IncludeResolver includeResolver = new IncludeResolver(parentDir);
        shaderc_compile_options_set_include_callbacks(instance.shaderc_compile_options, includeResolver, new IncludeResolver.Releaser(), 0);

        long spirV = shaderc_compile_into_spv(instance.shaderc_compiler, glsl, type.shadercGlslType, inputFile.getName(), GLSL_ENTRY_POINT, instance.shaderc_compile_options);
        checkResult(spirV, shaderc_result_get_compilation_status(spirV));

        return new Result(spirV, type);
    }

    private static void checkResult(long compile_result, int status_code) {
        if (status_code == shaderc_compilation_status_success) return;
        String msg = shaderc_result_get_error_message(compile_result);
        shaderc_result_release(compile_result);
        throw new ShaderException("ShaderC compile failed: error " + status_code + " - " + msg);
    }

    public static class Result implements AutoCloseable {
        private final long result;

        private Result(long result, ShaderType type) {
            this.result = result;
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
