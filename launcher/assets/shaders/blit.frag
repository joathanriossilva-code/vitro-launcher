// Papel de parede: uma leitura de textura por pixel e nada mais.
uniform sampler2D u_wall;
uniform float u_mul;
void main(){gl_FragColor=vec4(texture2D(u_wall,v_w).rgb*u_mul,1.0);}
