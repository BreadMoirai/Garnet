#version 330

// RedstoneSpecs clean-room blit shader.
// Positions arrive already in normalized device coordinates (the CPU maps the
// target sub-rect to NDC), so there is no projection/model-view uniform here.
// This is deliberately simpler than vanilla's position_tex.vsh, which multiplies
// by ProjMat * ModelViewMat.

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position, 1.0);
    texCoord = UV0;
}
