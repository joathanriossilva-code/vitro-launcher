// Sombra suave de um bloco: a borda de um retângulo arredondado passada por um desfoque gaussiano,
// calculada pela distância (aproximação da função erro), sem textura e sem desfocar nada a cada quadro.
uniform vec4 u_shape;
uniform float u_r;
uniform float u_sigma;
uniform vec4 u_color;  // já multiplicada pelo alfa
void main(){
  float x=sdRR(v_p-u_shape.xy,u_shape.zw,u_r)/(u_sigma*1.41421356);
  float e=sign(x)*sqrt(1.0-exp(-x*x*1.27323954));
  gl_FragColor=u_color*(0.5-0.5*e);
}
