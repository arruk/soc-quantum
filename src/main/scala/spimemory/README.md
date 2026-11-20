# Memória SPI para testar o bootloader

`SpiRom` é uma fonte de dados sintetizável, separada do pacote `soc`.
Usa `SyncReadMem` de bytes, inicializada por HEX com `loadMemoryFromFileInline`.
O fluxo é suportado pelo [Chisel para inicialização em FPGA](https://www.chisel-lang.org/docs/appendix/experimental-features#loading-memories-for-simulation-or-fpga-initialization).

| Propriedade | Valor |
|---|---|
| SPI | Modo 0, MSB primeiro |
| Comando | `03`, seguido de endereço de 24 bits, byte mais significativo primeiro |
| Dados | Bytes sequenciais, sem byte dummy após o endereço |
| CS | Ativo baixo; subir CS cancela a operação e reinicia o parser |
| Capacidade padrão | 131072 bytes (128 KiB), parametrizável em potência de dois |
| Endereço fora da capacidade | Retorna `FF`, sem espelhar os endereços baixos |
| Outros comandos | Ignorados até CS subir |
| MISO | `misoOe` ativo durante a fase de dados; `miso` vale zero fora dela |

O arquivo contém **um byte por entrada HEX**, com índices locais a partir de zero.
O endereço no protocolo é um deslocamento nessa imagem; não é um endereço do SoC.
Preencha a capacidade completa para não deixar conteúdo indefinido. O Makefile
em `TRYonRISCV/benchmarks/common_soc` gera esse formato usando `objcopy`.

O bloco usa o clock de sistema para detectar bordas de SCLK. Foi projetado para
o mestre interno do SoC no **mesmo domínio de clock**, com pelo menos dois ciclos
de sistema por meio período de SCLK (`clksPerHalfBit >= 2`). Não possui
sincronizadores para um mestre externo assíncrono.

`SystemWithSpiRom` instancia o SoC e a fonte SPI, ligando SCLK/MOSI/MISO/CS aos
pinos lógicos 0/1/2/3. A UART permanece exposta. Os demais pinos mantêm a interface
separada de entrada, saída e habilitação do SoC; o módulo de placa fornece os
buffers físicos, clock, reset e constraints. O wrapper desabilita a carga por
arquivo de PROGRAM/DATARAM: a aplicação existe inicialmente apenas na fonte SPI.

## Testar no Verilator

Na raiz de `soc-quantum`:

```sh
sbt "testOnly spimemory.SpiRomSpec"
```

Os quatro testes verificam a sequência de bytes, endereços não alinhados,
passagem entre páginas, pausas, limites, endereço completo de 24 bits, comando
inválido, cancelamento parcial e reset. O último teste usa `soc.SPI` com o
controlador Nandland real, acessado por `MemoryPort`, nos divisores 2 e 4.
Os testes usam ChiselSim com backend Verilator e desabilitam a randomização da
memória para preservar o HEX carregado.

## Gerar o sistema

A partir da raiz de TRYonRISCV:

```sh
make -C generators/soc-quantum CONFIG=spi BENCHMARK=hello
```

O Makefile prepara as imagens de firmware e acompanha suas dependências.
A saída fica em `generators/soc-quantum/build/spi/hello/50000000-115200/`.
Clock e baud rate são definidos em `config.mk` do gerador.

Para gerar apenas a fonte SPI:

```sh
CC_SPI_IMAGE=../../benchmarks/build/soc/hello/hello.spi.hex \
sbt "runMain spimemory.SpiRom --target-dir build/spi-rom"
```

`CC_SPI_BYTES` altera a capacidade; o HEX deve ter essa mesma capacidade.
Compile o RTL com `ENABLE_INITIAL_MEM_` também na síntese para habilitar a carga
da ROM emitida pelo firtool. Preserve o arquivo de inicialização no caminho
referenciado pelo RTL. O reset limpa o protocolo, sem apagar a ROM.

O teste do bloco não executa a CPU. Para a simulação completa, a partir da raiz
de TRYonRISCV, execute `make -C cc-sim hello`. Esse alvo seleciona
`SystemWithSpiRom` no wrapper do Verilator e envia `L`, `G`, `G` pela UART.
O runner confere a carga, duas execuções do hello e o retorno ao bootloader.
O wrapper antigo com loopback permanece disponível para os demais alvos.
