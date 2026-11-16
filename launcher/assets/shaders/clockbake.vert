// Desenha o relógio de vidro na textura dele: o retângulo inteiro da textura, mas com a posição na tela
// (v_p) e a coordenada do papel de parede (v_w) de onde o relógio fica, para a refração ler o fundo certo.
// A linha de cima do relógio vai para a primeira linha da textura, como nas imagens de texto.
attribute vec2 a;
uniform vec4 u_rect;   // canto superior esquerdo, largura e altura do relógio na tela (px, y para baixo)
uniform vec2 u_res;
varying vec2 v_p;
varying vec2 v_uv;
varying vec2 v_w;
void main(){
  v_uv=a;
  v_p=u_rect.xy+a*u_rect.zw;
  v_w=vec2(v_p.x/u_res.x,1.0-v_p.y/u_res.y);
  gl_Position=vec4(a*2.0-1.0,0.0,1.0);
}
