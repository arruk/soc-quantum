# SoC Quantum

SoC em Chisel para integrar um processador RISC-V ao acelerador de simulação quântica QuantumCircuit (QC), com validação em FPGA e implementação futura em ASIC.

Arquitetura planejada:

- RISC-V com interfaces separadas de instruções e dados; alvo RV32I + Zmmul + Xicrc.
- BOOTROM, RAM de programa, RAM de dados e memória local do QC.
- QC, UART, SPI e GPIO acessados por registradores mapeados em memória.
- Clock, reset, TX, RX e oito IOs, quatro compartilhados com SPI.
- Bootloader com comandos UART `L` (carregar via SPI), `G` (executar) e `S` (consultar estado).
- Controle por polling, inicialmente sem interrupções, cache ou DMA.

## Geração

Requer Java, sbt e GNU Make. Na raiz do projeto:

```sh
make
```

Saída: `build/SystemOnChip.sv`. Fonte principal: `src/main/scala/soc/SystemOnChip.scala`.

## Estado atual

Estrutura inicial de geração. O módulo `SystemOnChip` ainda está vazio; os blocos e o firmware descritos acima serão implementados.
