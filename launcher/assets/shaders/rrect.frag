// Retângulo arredondado de cor sólida: destaque dos menus, bloco "Todos", divisores.
uniform vec4 u_shape;
uniform float u_r;
uniform vec4 u_color;  // já multiplicada pelo alfa
void main(){gl_FragColor=u_color*clamp(0.5-sdRR(v_p-u_shape.xy,u_shape.zw,u_r),0.0,1.0);}
