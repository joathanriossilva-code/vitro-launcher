// Sombra de uma peça de vidro. A malha é cortada onde a distância deixa de ser linear, então o vértice
// entrega ao fragmento um vetor cujo comprimento já é a distância até o canto arredondado.
attribute vec2 a;      // posição em px relativa ao centro da sombra
uniform vec4 u_shape;  // centro x, y, meia largura, meia altura (px)
uniform float u_r;
uniform vec2 u_res;
varying vec2 v_e;
void main(){
  v_e=max(abs(a)-(u_shape.zw-vec2(u_r)),0.0);
  vec2 p=u_shape.xy+a;
  gl_Position=vec4(p.x/u_res.x*2.0-1.0,1.0-p.y/u_res.y*2.0,0.0,1.0);
}
