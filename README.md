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

SPI, GPIO, arbitragem das memórias e o wrapper com memória SPI também estão
implementados. QC continua sendo uma extensão planejada.
As suítes verificam os blocos separadamente; o funcionamento dos benchmarks
completos depende também do core em `../../rtl/cores/cc/cctorv5.v`.

## Geração

O Makefile deste repositório controla a geração do hardware: top Chisel, mapa,
inicialização das memórias, imagens HEX, clock/baud e dependências de Scala,
recursos Verilog, core e arquivos sbt. O ambiente esperado é
`TRYonRISCV/generators/soc-quantum`.

```sh
make
make generate CONFIG=spi BENCHMARK=dhrystone
make generate CONFIG=soc
make generate CONFIG=benchmark BENCHMARK=spi_uart
```

| CONFIG | Top | Imagens e mapa |
|---|---|---|
| `spi` | `spimemory.SystemWithSpiRom` | Bootloader em BOOTROM e aplicação na fonte SPI; PROGRAM/DATARAM sem carga por arquivo |
| `soc` (padrão) | `soc.SystemOnChip` | Bootloader em BOOTROM e mapa padrão; periféricos externos expostos nos pinos |
| `benchmark` | `soc.SystemOnChip` | Mapa e HEX antigos de `benchmarks/common/`, com RAM inicializada |

A configuração padrão continua gerando `SystemOnChip`, com o bootloader.
O benchmark padrão é `hello` para `spi` e `dhrystone` para `benchmark`.
O vetor de reset do core acompanha `map.bootrom.base`, inclusive no mapa antigo
que começa em zero. A CPU mantém `0x00400000` como valor padrão do parâmetro
Verilog `RESET_PC`.

A geração chama o Makefile de firmware apropriado antes de conferir os HEX.
O código C, linker e startup continuam em `TRYonRISCV/benchmarks`. Não é preciso
compilar essas imagens separadamente antes de chamar o gerador.

Os valores compartilhados de `CLOCK_HZ` e `BAUD` ficam em `config.mk`, neste
repositório; o `cc-sim` lê o mesmo arquivo para decodificar a UART. Podem ser
sobrescritos na linha de comando. Se alterar flags de compilação do firmware,
limpe a aplicação antes de recompilar, conforme `benchmarks/common_soc/README.md`.

Saídas padrão: `build/<CONFIG>/<BENCHMARK>/<CLOCK_HZ>-<BAUD>/`, contendo o top
SystemVerilog, módulos auxiliares e `filelist.f`. Em `CONFIG=spi`, `program.bytes`
registra o tamanho da aplicação para o teste UART conferir seu comando `L`.
O diretório pode ser escolhido com `RTL_DIR=/caminho/absoluto`.

```sh
make print-rtl-dir CONFIG=spi BENCHMARK=hello
make firmware CONFIG=spi BENCHMARK=hello
make clean CONFIG=spi BENCHMARK=hello
```

O Makefile verifica tanto os arquivos listados em `filelist.f` quanto os
arquivos fonte. Um módulo auxiliar ausente também dispara nova geração.
Após atualizar o firmware, uma segunda chamada sem alterações não executa sbt.
`make clean` remove apenas o diretório de RTL da configuração selecionada.

Os nomes de variáveis `CC_*` são detalhes da chamada ao gerador Chisel e ficam
no Makefile daqui. No uso por Make, não precisam ser definidos no `cc-sim`.
O construtor `Ram` ainda permite `initializeFromFile = false` para testes de bloco.
No Verilator, `ENABLE_INITIAL_MEM_` habilita a carga das imagens emitida pelo
firtool. O caminho do HEX da fonte SPI precisa continuar acessível durante a
simulação.

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
make -C ../../cc-sim soc BENCHMARK=dhrystone
make -C ../../cc-sim soc BENCHMARK=coremark
```

Ele delega a geração ao Makefile daqui, compila o modelo Verilator e mostra a saída UART,
sem relatórios de desvios ou gravação de CSV.
