GEN_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
REPO_ROOT ?= $(abspath $(GEN_DIR)/../..)
BENCH_DIR := $(REPO_ROOT)/benchmarks
COMMON_DIR := $(BENCH_DIR)/common_soc
include $(GEN_DIR)/config.mk

CONFIG ?= soc
ifeq ($(CONFIG),benchmark)
BENCHMARK ?= dhrystone
else ifeq ($(CONFIG),soc)
BENCHMARK ?= bootrom
else
BENCHMARK ?= hello
endif
SBT ?= sbt
RTL_DIR ?= $(GEN_DIR)/build/$(CONFIG)/$(BENCHMARK)/$(CLOCK_HZ)-$(BAUD)
HW_DIR := $(abspath $(RTL_DIR))
FW_DIR := $(BENCH_DIR)/build/soc

ifeq ($(CONFIG),spi)
TOP := SystemWithSpiRom
MAIN_CLASS := spimemory.SystemWithSpiRom
BOOTROM_HEX := $(FW_DIR)/bootrom/bootrom.words.hex
SPI_HEX := $(FW_DIR)/$(BENCHMARK)/$(BENCHMARK).spi.hex
APP_BINARY := $(FW_DIR)/$(BENCHMARK)/$(BENCHMARK).bin
PROGRAM_BYTES := $(HW_DIR)/program.bytes
MEMORY_INPUTS := $(BOOTROM_HEX) $(SPI_HEX)
INIT_RAM := 0
BENCHMARK_MAP := 0
else ifeq ($(CONFIG),soc)
TOP := SystemOnChip
MAIN_CLASS := soc.SystemOnChip
BOOTROM_HEX := $(FW_DIR)/bootrom/bootrom.words.hex
MEMORY_INPUTS := $(BOOTROM_HEX)
INIT_RAM := 0
BENCHMARK_MAP := 0
else ifeq ($(CONFIG),benchmark)
TOP := SystemOnChip
MAIN_CLASS := soc.SystemOnChip
BOOTROM_HEX := $(BENCH_DIR)/build/hex/$(BENCHMARK).PROGROM.hex
DATA_HEX := $(BENCH_DIR)/build/hex/$(BENCHMARK).DATARAM.hex
MEMORY_INPUTS := $(BOOTROM_HEX) $(DATA_HEX)
INIT_RAM := 1
BENCHMARK_MAP := 1
else
$(error Unknown hardware CONFIG: $(CONFIG); use spi, soc or benchmark)
endif

OUTPUT := $(HW_DIR)/$(TOP).sv
FILELIST := $(HW_DIR)/filelist.f
SCALA_SOURCES := $(shell find "$(GEN_DIR)/src/main/scala" -type f -name '*.scala')
RESOURCE_SOURCES := $(shell find "$(GEN_DIR)/src/main/resources" -type f)
CORE_SOURCE := $(REPO_ROOT)/rtl/cores/cc/cctorv5.v
PROJECT_FILES := $(GEN_DIR)/build.sbt $(wildcard $(GEN_DIR)/project/*.sbt $(GEN_DIR)/project/*.properties) $(GEN_DIR)/Makefile $(GEN_DIR)/config.mk
GENERATED_RTL := $(abspath $(addprefix $(HW_DIR)/,$(shell cat "$(FILELIST)" 2>/dev/null)))
RTL_OUTPUTS := $(sort $(OUTPUT) $(FILELIST) $(GENERATED_RTL))

.DEFAULT_GOAL := generate
.PHONY: all run generate firmware rtl print-rtl-dir clean
all run: generate

# Check firmware first; the recursive make then sees the updated HEX timestamps.
generate: firmware
	$(MAKE) --no-print-directory -C "$(GEN_DIR)" rtl

firmware:
ifeq ($(CONFIG),benchmark)
	$(MAKE) -C "$(BENCH_DIR)" "$(BENCHMARK).pipeline.hex"
else
ifeq ($(CONFIG),spi)
	$(MAKE) -C "$(COMMON_DIR)" all APP=$(BENCHMARK) KIND=application BUILD_DIR="$(FW_DIR)/$(BENCHMARK)" SOC_CLOCK_HZ=$(CLOCK_HZ)
endif
	$(MAKE) -C "$(COMMON_DIR)" bootrom BUILD_DIR="$(FW_DIR)/bootrom"
endif
ifeq ($(CONFIG),spi)
	$(MAKE) --no-print-directory -C "$(GEN_DIR)" "$(PROGRAM_BYTES)"
endif

$(HW_DIR):
	mkdir -p "$@"

ifneq ($(PROGRAM_BYTES),)
$(PROGRAM_BYTES): $(APP_BINARY) | $(HW_DIR)
	@wc -c < "$<" > "$@"
endif

rtl: $(RTL_OUTPUTS)

# The filelist also makes a missing auxiliary Verilog file trigger elaboration.
$(RTL_OUTPUTS) &: $(SCALA_SOURCES) $(RESOURCE_SOURCES) $(CORE_SOURCE) $(PROJECT_FILES) $(MEMORY_INPUTS) | $(HW_DIR)
	cd "$(GEN_DIR)" && CC_BOOTROM="$(BOOTROM_HEX)" CC_DATARAM="$(DATA_HEX)" CC_SPI_IMAGE="$(SPI_HEX)" CC_SPI_BYTES=131072 CC_INIT_RAM=$(INIT_RAM) CC_SIM_BENCHMARK=$(BENCHMARK_MAP) CC_SIM_BOOTROM=1 CC_CLOCK_HZ=$(CLOCK_HZ) CC_BAUD=$(BAUD) $(SBT) -batch "runMain $(MAIN_CLASS) --target-dir $(HW_DIR)"
	test -f "$(OUTPUT)"
	test -f "$(FILELIST)"
	cd "$(HW_DIR)" && touch "$(TOP).sv" filelist.f $$(cat filelist.f)

print-rtl-dir:
	@printf '%s\n' "$(HW_DIR)"

clean:
	rm -rf "$(HW_DIR)"
