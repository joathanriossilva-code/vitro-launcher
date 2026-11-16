// Desfoque gaussiano separável de 9 amostras, aplicado em um quarto da resolução.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D u_tex; uniform vec2 u_size; uniform vec2 u_step;
void main(){
  vec2 uv=gl_FragCoord.xy/u_size;
  vec3 c=texture2D(u_tex,uv).rgb*0.2270270270;
  c+=(texture2D(u_tex,uv+u_step*1.3846153846).rgb+texture2D(u_tex,uv-u_step*1.3846153846).rgb)*0.3162162162;
  c+=(texture2D(u_tex,uv+u_step*3.2307692308).rgb+texture2D(u_tex,uv-u_step*3.2307692308).rgb)*0.0702702703;
  gl_FragColor=vec4(c,1.0);
}
