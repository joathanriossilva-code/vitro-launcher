// O relógio de vidro já pronto (ver Gfx.bakeClock): uma leitura de textura por pixel, como o texto.
// Com um menu aberto o fundo escurece, e o vidro, que mostra o fundo, escurece junto (a sombra não muda).
uniform sampler2D u_tex;
uniform vec2 u_k;   // opacidade, multiplicador de luz
void main(){vec4 c=texture2D(u_tex,v_uv);gl_FragColor=vec4(c.rgb*u_k.y,c.a)*u_k.x;}
