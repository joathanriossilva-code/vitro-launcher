// Imagem pronta (texto com sombra): textura já multiplicada pelo alfa.
uniform sampler2D u_tex;
uniform float u_alpha;
void main(){gl_FragColor=texture2D(u_tex,v_uv)*u_alpha;}
