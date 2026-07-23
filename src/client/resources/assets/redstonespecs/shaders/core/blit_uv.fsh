#version 330

// RedstoneSpecs clean-room blit shader.
// Straight texture copy of the bound source into the target sub-rect. Alpha is
// preserved (no discard) so callers can decide blending via the pipeline's
// ColorTargetState rather than in the shader.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, texCoord);
}
