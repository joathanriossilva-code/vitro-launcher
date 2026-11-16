// Halo do app em foco: o próprio banner, desfocado na borda e translúcido, por baixo do bloco.
// (Na primeira versão isso saía do desenhista do Android; aqui é a mesma aparência calculada pela distância.)
uniform sampler2D u_tex;
uniform vec4 u_shape;
uniform float u_r;
uniform float u_sigma;
uniform float u_alpha;
void main(){
  vec2 c=v_p-u_shape.xy;
  float x=sdRR(c,u_shape.zw,u_r)/(u_sigma*1.41421356);
  float e=sign(x)*sqrt(1.0-exp(-x*x*1.27323954));
  gl_FragColor=texture2D(u_tex,c/(2.0*u_shape.zw)+0.5)*(u_alpha*(0.5-0.5*e));
}
