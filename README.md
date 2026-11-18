# SoC Quantum

SoC em Chisel para integrar o processador RISC-V `torv32` ao acelerador
QuantumCircuit (QC). A arquitetura planejada está em [SOC.md](SOC.md).

## Estado atual

Implementados:

- Interfaces de requisição e resposta com `valid/ready` para instruções e dados.
- Mapas de memória com regiões opcionais e decodificação para offsets locais.
- ROM inicializada por palavras HEX e RAM síncrona de 32 bits com máscara por byte.
- UART mapeada em memória, usando os módulos `Tx` e `Rx` originais de Martin
  Schoeberl, incluídos como fonte em `src/main/scala/chisel/lib/uart/`.
- Adaptador Chisel para o core Verilog e integração inicial de CPU, ROM, RAM e UART.

QC, SPI, GPIO, arbitragem das memórias compartilhadas e bootloader UART/SPI ainda
são extensões planejadas. As saídas de pinos estão fixadas em zero no top atual.
As suítes verificam os blocos separadamente; o funcionamento dos benchmarks
completos depende também do core em `../../rtl/cores/cc/cctorv5.v`.

## Geração

Requer Java, sbt e GNU Make. A estrutura de diretórios esperada é
`TRYonRISCV/champion-chip/soc-quantum`, com o core e os benchmarks no repositório pai.
Na raiz deste projeto:

```sh
make -C ../../benchmarks dhrystone.pipeline.hex
make
```

Saída: `build/SystemOnChip.sv`, módulos auxiliares e `filelist.f`. Fonte principal:
`src/main/scala/soc/SystemOnChip.scala`.

A geração aceita estas variáveis de ambiente:

| Variável | Função |
|---|---|
| `CC_PROGROM` | Caminho do HEX de instruções; padrão: Dhrystone no repositório pai |
| `CC_DATARAM` | Caminho do HEX de dados; padrão: Dhrystone no repositório pai |
| `CC_CLOCK_HZ` | Frequência usada pela UART; padrão: `50000000` |
| `CC_BAUD` | Baud rate da UART; padrão: `115200` |
| `CC_SIM_BENCHMARK=1` | Seleciona o mapa dos benchmarks antigos |

`MemoryMaps.benchmark` usa ROM em `0x00000000` e RAM em `0x00010000`, ambas com
64 KiB, além da UART em `0x00400000`. Os presets `quantum` e `simpler` atualmente
compartilham a configuração base de `SocMemoryMap`.

O HEX da ROM é incorporado durante a geração. A RAM usa `$readmemh` no RTL gerado;
seu construtor mantém a carga do arquivo habilitada por padrão:

```scala
val dataRam = Module(new Ram(16384))
```

Para memórias pequenas de testes, desabilite a carga do HEX completo:

```scala
val testRam = Module(new Ram(4, initializeFromFile = false))
```

O caminho continua sendo resolvido dentro de `Ram`; não é necessário passá-lo ao
construtor. No simulador Verilator, `ENABLE_INITIAL_MEM_` habilita a inicialização
emitida pelo Chisel.

## Testes

Requer também Verilator, Make e um compilador C++:

```sh
sbt test
# Ou apenas as suítes de blocos:
sbt "testOnly soc.DecoderSpec soc.MemorySpec soc.UartSpec"
```

Os testes dos decodificadores cobrem configurações completas, mínimas e com
opcionais intercalados, além de espera, retenção da resposta e reset. Os testes de
memória verificam máscaras por byte, ROM e RAM de uma palavra sem carregar imagens
de benchmark. A UART verifica TX/RX, polling, retenção dos bytes e overflow.

## Benchmarks

O ambiente de simulação está em `TRYonRISCV/cc-sim`. A partir deste projeto:

```sh
make -C ../../cc-sim BENCHMARK=dhrystone
make -C ../../cc-sim BENCHMARK=coremark
```

Ele seleciona os HEX, gera o SoC, compila o modelo Verilator e mostra a saída UART,
sem relatórios de desvios ou gravação de CSV.
