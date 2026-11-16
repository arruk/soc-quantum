# SoC — arquitetura inicial

```text
                         SoC
        ┌────────────────────────────────────────────────┐
clk_i ──► Clock para todos os blocos                      │
rst_i ──► Controle de reset                               │
        │                                                │
        │   ┌───────────┐                                │
        │   │   RISC-V  │                                │
        │   └─────┬─────┘                                │
        │         │ instruções / dados / I/O             │
        │   ┌─────▼──────────────────────────────────┐   │
        │   │ Interconexão e decodificação           │   │
        │   └──┬────────┬──────┬──────┬───────┬──────┘   │
        │      │        │      │      │       │          │
        │   BOOTROM    RAM     QC    UART  SPI / GPIO    │
        │                      │      │       │          │
        │                 RAM local   │       │          │
        │                 do vetor    │       │          │
        └─────────────────────────────┼───────┼──────────┘
                                      │       │
                               tx_o / rx_i  pins_io[7:0]
```

IMEM atende busca de instruções e leitura de constantes. Com uma porta, exige arbitragem e espera na CPU.

| Bloco | Endereço base / função |
|---|---|
| BOOTROM / IMEM | `0x00400000` — firmware fixo |
| DMEM | `0x10010000` — dados e pilha |
| QC, UART, SPI e GPIO | Registradores mapeados em memória; endereços a definir |

## RISC-V — interfaces de memória

```text
RISC-V
 ├─ Instruções ──► Decodificador ──► BOOTROM
 │                       └───────► RAM de programa
 │
 └─ Dados ───────► Decodificador ──► RAM de dados
                         ├───────► BOOTROM (constantes)
                         ├───────► RAM de programa*
                         └───────► QC / UART / SPI / GPIO
```

*A interface de dados permite carregar e ler programas. A CPU executa na BOOTROM durante a carga via UART/SPI.

RAM de programa: extensão planejada, com endereço e capacidade a definir. Se houver acessos simultâneos à mesma memória de uma porta, será necessária arbitragem com espera na CPU.

### Conexões das memórias

```text
                       ┌───────────┐
                       │  RISC-V   │
                       └──┬─────┬──┘
              instruções  │     │ dados
                   ┌──────▼─┐ ┌─▼──────┐
                   │ Dec. I │ │ Dec. D │
                   └──┬──┬──┘ └─┬─┬─┬──┘
                      │  │      │ │ └─────────────────┐
      ┌───────────────┘  │      │ │                   │
      │  ┌───────────────┼──────┘ │                   │
      │  │               │        │                   │
   ┌──▼──▼───┐        ┌──▼────────▼─┐                 │
   │ Árbitro │        │  Árbitro    │                 │
   └────┬────┘        └──────┬──────┘                 │
        │                    │                        │
   ┌────▼────┐        ┌──────▼─────┐          ┌───────▼─────┐
   │ BOOTROM │        │ RAM de     │          │ RAM de dados│
   │         │        │ programa   │          │             │
   └─────────┘        └────────────┘          └─────────────┘
```

Cruzamento de fios sem conexão. Árbitros para memórias de uma porta; o caminho de retorno dos dados foi omitido.

## GPIO, SPI e UART

```text
Interface de dados do RISC-V
              │
      ┌───────▼────────┐
      │ Decodificador D│
      └──┬──────┬────┬─┘
         │      │    │
    ┌────▼─┐ ┌──▼──┐ │
    │ GPIO │ │ SPI │ │
    └──┬───┘ └──┬──┘ │
       │        │    │
    ┌──▼────────▼─┐ ┌▼─────┐
    │ Seleção de  │ │ UART │
    │ função/pino │ └──┬───┘
    └──────┬──────┘    │
           │           │
      pins_io[7:0]  tx_o / rx_i
```

O endereço seleciona os registradores do periférico. A configuração dos pinos seleciona GPIO ou SPI em `pins_io[3:0]`; `pins_io[7:4]` permanecem GPIO. Entradas e saídas são encaminhadas conforme a função; a UART usa pinos dedicados.

### UART — registradores

```text
                     UART
         ┌────────────────────────┐
rx_i ───►│ Receptor → RX_DATA      │──► CPU lê
         │            RX_READY    │──► CPU consulta
         │                        │
tx_o ◄───│ Transmissor ← TX_DATA   │◄── CPU escreve
         │              TX_READY  │──► CPU consulta
         └────────────────────────┘
```

| Registrador | Acesso da CPU | Função |
|---|---|---|
| `RX_DATA` | Leitura | Último byte recebido |
| `TX_DATA` | Escrita | Byte a transmitir |
| `STATUS` | Leitura | Flags `RX_READY` e `TX_READY` |
| `CONTROL` | Leitura/escrita | Configuração, como divisor de baud rate |

Acessos via decodificador D. Inicialmente, um byte por direção, sem FIFO. Ler `RX_DATA` limpa `RX_READY`; escrever em `TX_DATA` exige `TX_READY`. Overflow de recepção sinalizado em `STATUS`.

## QuantumCircuit (QC)

```text
Decodificador D
       │
┌──────▼─────────────────────┐
│ Adaptador QC               │
│                            │
│ store Q_CMD ────────────────┼──► cmd
│ load  Q_STATUS ◄────────────┼─── status
│                            │
│ load/store Q_STATE[i]       │
│       ↕                    │
│ endereço, dados, enables    │
└──────┬─────────────────────┘
       ↕
 QuantumCircuit
       ↕
 RAM do vetor
```

| Região/endereço | Acesso | Função |
|---|---|---|
| `Q_CMD` | Escrita | Solicitar uma operação |
| `Q_STATUS` | Leitura | Consultar disponível, ocupado ou erro |
| `Q_STATE + 4*i` | Leitura/escrita | Acessar a palavra `i` do vetor |

Endereços a definir. Cada amplitude complexa ocupa duas palavras de 32 bits: real e imaginária.

```text
store → inicializar palavras do vetor
store → enviar comando
load  → consultar status até concluir
load  → ler cada palavra desejada do vetor
```

O adaptador respeita a leitura síncrona da RAM, bloqueia acessos ao vetor durante a execução e retorna `cmd` a zero entre operações.

### Sinais entre adaptador e QC

```text
Decodificador D
       ↕
┌──────────────┐                  ┌────────────────┐
│              │── cmd ──────────►│                │
│ Adaptador QC │◄─ status ────────│ QuantumCircuit │
│              │◄─ mem_data ──────│                │
│              │── mem_addr ─────►│                │
│              │── mem_wdata ────►│                │
│              │── mem_ren ──────►│                │
│              │── mem_wen ──────►│                │
│              │── mem_wmask ────►│                │
└──────────────┘                  └────────────────┘
```

Sinais internos ao SoC. A RAM do vetor fica dentro do QuantumCircuit. Clock e reset omitidos.

## Bootloader via SPI

Na validação em FPGA, a memória de origem é previamente preenchida. O bootloader executa na BOOTROM; cada palavra usa uma transação SPI, com clock e `CS` gerenciados pelo controlador.

### Comandos via UART

Após configurar a UART e a pilha, o bootloader aguarda comandos de um byte por polling.

```text
Consultar RX_READY ◄────────────┐
       │                       │
       ├─ 0 ───────────────────┘
       │
       └─ 1 → Ler RX_DATA
                    ↓
             Interpretar comando
                    ↓
               Executar ação
                    ↓
             Voltar ao polling
```

| Comando UART | Ação |
|---|---|
| `L` | Carregar programa e dados via SPI; responder `LOADED` |
| `G` | Saltar para a entrada do programa carregado |
| `S` | Informar o estado atual pela UART |

Ler `RX_DATA` da UART limpa `RX_READY`. O emissor aguarda a resposta antes do próximo comando. Em `G`, o polling só retorna quando a aplicação devolve o controle ao bootloader, que sinaliza `DONE`. Origem, destinos, tamanho e entrada são inicialmente constantes no firmware.

### Carga via SPI (comando L)

```text
         ┌───────────────────────┐
         │ Configuração inicial  │
         └───────────┬───────────┘
                     ▼
         ┌───────────────────────┐◄──────────────┐
         │ Enviar comando de     │               │
         │ leitura e endereço    │               │
         └───────────┬───────────┘               │
                     ▼                           │
         ┌───────────────────────┐               │
         │ Solicitar recepção    │               │
         │ de uma palavra        │               │
         └───────────┬───────────┘               │
                     ▼                           │
         ┌───────────────────────┐               │
    ┌───►│ Consultar status SPI  │               │
    │    └───────────┬───────────┘               │
    │                ▼                           │
    │       Dado disponível?                     │
    └── não ─────────┤                           │
                     │ sim                       │
                     ▼                           │
         ┌───────────────────────┐               │
         │ load RX_DATA          │               │
         │ para registrador CPU  │               │
         └───────────┬───────────┘               │
                     ▼                           │
         ┌───────────────────────┐               │
         │ store na RAM destino  │               │
         └───────────┬───────────┘               │
                     ▼                           │
         ┌───────────────────────┐               │
         │ Atualizar endereços   │               │
         │ e contador            │               │
         └───────────┬───────────┘               │
                     ▼                           │
             Restam palavras? ─── sim ───────────┘
                     │ não
                     ▼
         ┌───────────────────────┐
         │ Sinalizar LOADED      │
         │ Voltar ao polling UART│
         └───────────────────────┘
```

- **Configuração:** definir `sp`, selecionar SPI nos pinos, configurar clock/modo e preparar origem, destino, quantidade e endereço de entrada.
- **Comando de leitura:** enviar a operação e o endereço da palavra na memória de origem.
- **Recepção:** solicitar ao controlador a leitura de 32 bits para `RX_DATA`.
- **Status:** consultar até a recepção terminar; o controlador mantém o dado disponível.
- **Load:** copiar `RX_DATA` para um registrador da CPU.
- **Store:** gravar a palavra na RAM de programa ou de dados.
- **Atualização:** avançar os endereços em quatro bytes e decrementar o contador.
- **Repetição:** iniciar outra transação enquanto houver palavras pendentes.
- **Conclusão:** responder `LOADED` e aguardar outro comando UART. O salto ocorre somente com `G`; a inicialização do programa prepara `.bss` antes da aplicação.

## Pinos

| Pino | Função |
|---|---|
| `clk_i` | Clock de entrada |
| `rst_i` | Reset de entrada |
| `tx_o` / `rx_i` | UART |
| `pins_io[0]` | GPIO ou SPI SCLK (saída) |
| `pins_io[1]` | GPIO ou SPI MOSI (saída) |
| `pins_io[2]` | GPIO ou SPI MISO (entrada) |
| `pins_io[3]` | GPIO ou SPI CS (saída) |
| `pins_io[7:4]` | GPIO |

SPI conecta memória/periféricos externos. A CPU transfere dados com `load/store` nos registradores do controlador. Sem cache ou DMA nesta versão.

Alvo do guia: RV32I + Zmmul + Xicrc. Capacidades das memórias e área a validar.
