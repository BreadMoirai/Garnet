#version 330

// garnet clean-room blit shader (vertex stage).
// Vertices arrive already in normalized device coordinates — the caller maps the
// destination sub-rect into clip space on the CPU — so this stage does no
// ProjMat/ModelViewMat transform (unlike vanilla's position_tex.vsh). It only
// forwards the sampling coordinate to the fragment stage.
// Attribute names Position/UV0 are fixed by DefaultVertexFormat.POSITION_TEX.

in vec3 Position;
in vec2 UV0;

out vec2 v_uv;

void main() {
    v_uv = UV0;
    gl_Position = vec4(Position.xyz, 1.0);
}
