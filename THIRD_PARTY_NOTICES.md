# Créditos de terceiros · Third-party notices

O Vitro é distribuído sob a licença MIT (veja [LICENSE](LICENSE)). Partes dele vêm de outros projetos ou se
baseiam neles, com estas licenças. *Vitro is MIT-licensed; these parts come from or build on other projects.*

## FireFrame — MIT

Base da lista de debloat do Fire OS (`launcher/debloat.txt`) e da forma de compilar sem Gradle (`launcher/build.sh`).
https://github.com/dyascj/FireFrame

```
MIT License

Copyright (c) 2026 CJ Dyas

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## TV Detox — MIT

Forma de compilar sem Gradle (`launcher/build.sh`). https://github.com/Ansub/tv-detox

```
MIT License

Copyright (c) 2026 Ansub Khan

(Mesmo texto da licença MIT acima. Same MIT license text as above.)
```

## M PLUS Rounded 1c — SIL Open Font License 1.1

Fonte do relógio. Texto completo em [`launcher/assets/fonts/OFL.txt`](launcher/assets/fonts/OFL.txt).

## Papéis de parede · Wallpapers

Fotos e obras de domínio público ou CC0, com a fonte de cada uma em
[`launcher/assets/wallpapers/CREDITS.md`](launcher/assets/wallpapers/CREDITS.md). Leque e Ondas são desenhos
originais do Vitro, sob a mesma licença MIT.

## Ideias e protocolos (sem código copiado) · Ideas and protocols (no code copied)

- **Homestead:** a VPN local para alcançar o ADB da própria Fire TV e a leitura do registro para perceber o Home.
- **dumbtv** e **firetv-home-guard:** o alarme imediato do botão Home.
- **adb (Android Open Source Project, Apache 2.0)** e **BoringSSL:** o protocolo de pareamento da Depuração sem fio
  (TLS 1.3, SPAKE2 sobre Ed25519, HKDF, AES-GCM) foi reescrito em Java a partir da descrição e do código-fonte
  publicados; nenhum trecho foi copiado. As constantes da curva (pontos M e N) são valores técnicos públicos.
- Listas de debloat do Android TV conferidas no Universal Android Debloater (UAD-ng) e no CleanYourTV.
