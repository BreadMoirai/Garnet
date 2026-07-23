#version 330

// RedstoneSpecs clean-room blit shader (fragment stage).
// Straight copy of the bound source texture into the destination sub-rect. Alpha
// is passed through (no discard) so the pipeline's ColorTargetState — not the
// shader — decides how the result blends onto the target.
// Sampler name InSampler matches the binding in BlitUvPipeline.

uniform sampler2D InSampler;

in vec2 v_uv;

out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, v_uv);
}
