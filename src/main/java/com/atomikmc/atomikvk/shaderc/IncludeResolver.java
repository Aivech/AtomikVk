package com.atomikmc.atomikvk.shaderc;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

public class IncludeResolver implements ShadercIncludeResolveI {
    private final File[] includeDirectories;

    public IncludeResolver(File... directories) {
        includeDirectories = directories;
    }


    @Override
    public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
        ShadercIncludeResult include = ShadercIncludeResult.calloc();
        String request = MemoryUtil.memUTF8(requested_source);
        for (File dir : includeDirectories) {
            for (File file : dir.listFiles()) {
                if (file.getName().equals(request)) {
                    try {
                        ByteBuffer glsl = MemoryUtil.memUTF8(Files.readString(file.toPath()));
                        include.content(glsl);
                        include.source_name(MemoryUtil.memByteBufferNT1(requested_source));
                    } catch (IOException e) {
                        throw new ShaderException("Exception while loading include file \"" + request + "\" for \"" + MemoryUtil.memUTF8(requesting_source) + "\"", e);
                    }
                }
            }
        }
        return include.address();
    }

    public static class Releaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long user_data, long include_result) {
            ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
            ByteBuffer glsl = result.content();
            MemoryUtil.memFree(glsl);
            result.free();
        }
    }
}
