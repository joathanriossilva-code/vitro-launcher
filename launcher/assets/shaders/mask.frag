// Texto e ícones de uma cor só: a textura guarda só a cobertura, a cor vem de fora.
uniform sampler2D u_tex;
uniform vec4 u_color;  // já multiplicada pelo alfa
void main(){gl_FragColor=u_color*texture2D(u_tex,v_uv).a;}
