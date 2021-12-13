package com.atomikmc.atomikvk.common.resource;

import com.atomikmc.atomikvk.shaderc.ShaderException;
import com.atomikmc.atomikvk.shaderc.SpirVCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ShaderResource implements AutoCloseable {
    public final ShaderType type;
    private final SpirVCompiler.Result spirV;

    /**
     * Creates a compiled SPIR-V shader resource from the specified file
     * @param glsl The GLSL shader file to compile
     * @param type A ShaderType value representing the type of shader specified by the file
     */
    public ShaderResource(File glsl, ShaderType type) {
        try {
            spirV = SpirVCompiler.compile(glsl, type);
            this.type = type;
        } catch (IOException e) {
            throw new ShaderException("Failed to compile shader", e);
        }
    }

    /**
     * Same as above, but assumes type from filename
     * @param glsl The GLSL shader file to compile
     */
    public ShaderResource(File glsl) {
        this(glsl, getShaderType(glsl.getName()));
    }

    /**
     * Get the compiled SPIR-V bytecode.
     * @return A ByteBuffer containing the compiled shader.
     */
    public ByteBuffer getBytes() {
        return spirV.bytes();
    }

    @Override
    public void close() {
        spirV.close();
    }

    public static ShaderType getShaderType(String fileName) {
        if(fileName.endsWith(".frag")) return ShaderType.FRAG;
        if(fileName.endsWith(".vert")) return ShaderType.VERT;
        if(fileName.endsWith(".comp")) return ShaderType.COMP;
        throw new ShaderException("Unknown shader type for file \""+fileName+"\"");
    }
}
