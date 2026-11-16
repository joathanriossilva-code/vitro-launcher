// Banner de um app com cantos arredondados. O retângulo desenhado sobra 1 px em volta do bloco para a
// borda ficar suave; por isso a coordenada da textura sai da posição, não do retângulo.
uniform sampler2D u_tex;
uniform vec4 u_shape;
uniform float u_r;
uniform float u_alpha;
void main(){
  vec2 c=v_p-u_shape.xy;
  gl_FragColor=texture2D(u_tex,c/(2.0*u_shape.zw)+0.5)*(u_alpha*clamp(0.5-sdRR(c,u_shape.zw,u_r),0.0,1.0));
}
