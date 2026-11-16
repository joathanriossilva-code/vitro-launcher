// Um retângulo na tela. O fragmento recebe a posição em px e a coordenada do papel de parede.
attribute vec2 a;
uniform vec4 u_rect;   // x, y do canto superior esquerdo, largura, altura (px, y para baixo)
uniform vec2 u_res;
varying vec2 v_p;
varying vec2 v_uv;
varying vec2 v_w;
void main(){
  v_uv=a;
  v_p=u_rect.xy+a*u_rect.zw;
  v_w=vec2(v_p.x/u_res.x,1.0-v_p.y/u_res.y);
  gl_Position=vec4(v_w.x*2.0-1.0,v_w.y*2.0-1.0,0.0,1.0);
}
