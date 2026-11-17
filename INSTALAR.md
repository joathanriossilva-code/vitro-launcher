# Como instalar o Vitro

*English: [INSTALL.md](INSTALL.md).* Depois de instalar, veja a [primeira configuração](README.md#primeira-configura%C3%A7%C3%A3o)
e o [tutorial de uso](TUTORIAL.md).

O Vitro não está na loja da Amazon nem na do Google: ele é instalado pelo arquivo `vitro.apk`, publicado na página de
versões (**Releases**) deste repositório. Leva uns 5 minutos. Os jeitos estão do mais fácil para o mais trabalhoso:
escolha um.

| Jeito | Precisa de | Bom para |
|---|---|---|
| [1. App Downloader](#1-app-downloader-o-mais-f%C3%A1cil) | Só a TV e o controle | Quase todo mundo |
| [2. Do celular, com o Send Files to TV](#2-do-celular-com-o-send-files-to-tv) | Um celular Android | Quem não quer digitar endereço com o controle |
| [3. Pen drive](#3-pen-drive) | Um pen drive e uma TV ou box com USB | TVs e boxes Android TV (o Fire TV Stick não tem USB) |
| [4. Computador, com o ADB](#4-computador-com-o-adb) | Um computador na mesma rede | Quem já usa o ADB |

## Antes de começar

1. **Confira se a sua TV é compatível** na tabela [Onde funciona](README.md#onde-funciona). TVs Samsung (Tizen), LG
   (webOS), Roku e os Fire TV com Vega OS não instalam apps Android.
2. **Ligue as Opções do desenvolvedor.** O Vitro precisa delas na primeira configuração, e em algumas TVs elas são a
   chave para liberar a instalação de apps de fora da loja.

   | Sistema | Caminho |
   |---|---|
   | Fire TV | Configurações › **Minha Fire TV** › Sobre › aperte OK **7 vezes** sobre o nome do aparelho |
   | Google TV | Configurações › **Sistema** › Sobre › aperte OK **7 vezes** sobre "Build do SO do Android TV" |
   | Android TV | Configurações › **Preferências do dispositivo** › Sobre › aperte OK **7 vezes** sobre "Build" |

   Aparece o aviso de que você agora é um desenvolvedor. Os nomes mudam um pouco de um fabricante para outro.

## 1. App Downloader (o mais fácil)

O **Downloader** é um app gratuito que baixa arquivos direto na TV, sem precisar de outro aparelho.

1. **Instale o Downloader.** No Fire TV, procure "Downloader" na loja (a lupa) e instale o de ícone laranja. No Google
   TV e no Android TV, ele está na Google Play.
2. **Deixe o Downloader instalar apps.**

   | Sistema | Caminho |
   |---|---|
   | Fire TV | Configurações › Minha Fire TV › Opções do desenvolvedor › **Instalar apps desconhecidos** › Downloader: **Ligado** |
   | Google TV | Configurações › Apps › Segurança e restrições › **Fontes desconhecidas** › Downloader: **Ligado** |
   | Android TV | Configurações › Preferências do dispositivo › Segurança e restrições › **Fontes desconhecidas** › Downloader: **Ligado** |

3. **Abra o Downloader** e, no campo de endereço, digite só o código:

   ```
   9849078
   ```

   Aperte **Go**. O download começa sozinho. O código sempre leva à versão mais recente do Vitro.

   <details>
   <summary>Se preferir digitar o endereço completo</summary>

   ```
   github.com/joathanriossilva-code/vitro-launcher/releases/latest/download/vitro.apk
   ```

   É para onde o código leva (dá para conferir em `aftv.news/9849078+`, em qualquer navegador).
   </details>
4. Quando terminar, aperte **Instalar** e depois **Concluído**. O Downloader oferece apagar o arquivo baixado: pode
   apagar.

> Prefere escolher a versão? Digite `github.com/joathanriossilva-code/vitro-launcher/releases` no Downloader, que tem
> navegador, e escolha o `vitro.apk` da versão que quiser.

## 2. Do celular, com o Send Files to TV

Bom para quem não quer digitar o endereço com o controle.

1. Instale o app **Send Files to TV** na TV (loja da Amazon ou Google Play) e no celular Android (Google Play).
2. Deixe o Send Files to TV instalar apps na TV, pelo mesmo caminho do passo 2 do Downloader, mas ligando o
   **Send Files to TV**.
3. No celular, baixe o `vitro.apk` da página de versões (**Releases**) deste repositório.
4. Abra o app nos dois aparelhos: na TV, escolha **Receber**; no celular, **Enviar**, escolha o `vitro.apk` e a sua TV.
5. Na TV, abra o arquivo recebido e aperte **Instalar**.

## 3. Pen drive

Para TVs e boxes com entrada USB.

1. No computador, baixe o `vitro.apk` da página de versões (**Releases**) e copie para um pen drive.
2. Na TV, instale um gerenciador de arquivos (por exemplo, **X-plore File Manager**, na Google Play) e deixe-o
   instalar apps, pelo caminho do passo 2 do Downloader.
3. Ligue o pen drive na TV, abra o gerenciador de arquivos, entre no pen drive, escolha o `vitro.apk` e aperte
   **Instalar**.

## 4. Computador, com o ADB

Para quem já tem o [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) no
computador.

1. Ligue a **Depuração ADB** (Fire TV) ou a **Depuração USB** (Android TV e Google TV) nas Opções do desenvolvedor.
2. Descubra o IP da TV: Configurações › Rede (no Fire TV, Minha Fire TV › Sobre › Rede).
3. No computador, com o `vitro.apk` baixado:

   ```
   adb connect IP-DA-TV:5555
   adb install vitro.apk
   ```

   Na primeira vez, a TV pergunta se permite a depuração desse computador: marque **Sempre permitir** e aperte **OK**.

**TVs com Android 13 e 14 sem a porta 5555** (o `adb connect` falha): ligue a **Depuração sem fio** nas Opções do
desenvolvedor, entre em **Parear dispositivo com código de pareamento** e use o IP, as portas e o código que a TV mostra:

```
adb pair IP-DA-TV:PORTA-DE-PAREAMENTO
adb connect IP-DA-TV:PORTA-DA-DEPURAÇÃO-SEM-FIO
adb install vitro.apk
```

## Depois de instalar

1. **Abra o Vitro**: no Fire TV, em Apps (ou segure Home › Apps); no Google TV, na aba Apps; no Android TV, na fileira
   de apps.
2. A **primeira configuração** abre sozinha e guia o resto, inclusive o botão Home. Veja
   [Primeira configuração](README.md#primeira-configura%C3%A7%C3%A3o).
3. Para conhecer cada botão e menu, veja o [tutorial de uso](TUTORIAL.md).

## Atualizar e desinstalar

- **Atualizar:** instale o `vitro.apk` novo por cima, por qualquer um dos jeitos acima. Os ajustes continuam.
- **Desinstalar:** Configurações › Apps (ou Aplicativos) › Vitro › Desinstalar. Os apps que ele desativou continuam
  desativados até você ativá-los; veja [Desfazer](README.md#desfazer).

## Se der errado

| O que aparece | O que fazer |
|---|---|
| "Instalação bloqueada" ou o botão Instalar não aparece | Falta deixar o app que baixou o arquivo instalar apps (passo 2 do Downloader). |
| "App não instalado" | Já existe outra versão do Vitro assinada com outra chave (por exemplo, uma compilada por você). Desinstale a antiga e instale de novo. |
| O Downloader não acha o código ou o endereço | Confira os números (9849078) ou o endereço letra por letra, ou abra a página de versões pelo navegador do Downloader. |
| O app não aparece na lista | No Fire TV, veja em Apps › Ver tudo; em algumas TVs, o app novo vai para o fim da lista. |
