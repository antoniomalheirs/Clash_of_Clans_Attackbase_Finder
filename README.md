# Open Clans Screen Automation

![Android](https://img.shields.io/badge/Platform-Android-green) ![Kotlin/Java](https://img.shields.io/badge/Language-Java-orange)  

**Open Clans Screen Automation** é um aplicativo Android para captura de tela em tempo real e automação de toques baseada em reconhecimento de texto. Ele permite que o app detecte valores específicos na tela e execute toques automáticos apenas quando determinadas condições **não forem atendidas**, proporcionando uma automação inteligente e segura.  

---

## Funcionalidades

- **Captura de tela seletiva**: analisa apenas regiões específicas da tela para reduzir consumo de recursos.  
- **Reconhecimento de texto (OCR)**: identifica números e textos na tela usando **ML Kit**.  
- **Lógica condicional de toque**: aciona toques automáticos apenas quando os critérios configurados não forem atendidos.  
- **Serviço de acessibilidade seguro**: usado apenas para toques autorizados pelo usuário, seguindo as diretrizes do Android.  
- **Compatível com Android 14+**: integra `MediaProjection` e `Foreground Service` para captura contínua.  
- **Comunicação eficiente**: utiliza **LocalBroadcastManager** para integração entre a atividade principal e o serviço de acessibilidade.  

---

## Como Funciona

1. O usuário concede permissão de **acessibilidade** e **captura de tela**.  
2. O app inicia a captura contínua da tela ou de uma região específica (crop).  
3. O **ML Kit OCR** analisa o texto detectado.  
4. Quando os valores detectados **não atendem às condições** definidas, o serviço de acessibilidade realiza um toque automático.  
5. Se as condições forem atendidas, o toque é **omitido**, garantindo decisões inteligentes.  

---

## Requisitos

- Android 14 ou superior.  
- Permissão de **acessibilidade** para o serviço de toque automático.  
- Permissão de **captura de tela** via MediaProjection.  

---

## Instalação

1. Clone o repositório:  
```bash
git clone https://github.com/seu-usuario/open-clans-screen-automation.git
