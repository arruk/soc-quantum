PACKAGE = soc
CLASS = SystemOnChip
BUILD_DIR = build

OUTPUT = $(BUILD_DIR)/$(CLASS).sv
SCALA_SOURCES := $(shell find src/main/scala -type f -name '*.scala')

.PHONY: all run clean
all: run
run: $(OUTPUT)

clean:
	rm -rf $(BUILD_DIR)

$(OUTPUT): $(SCALA_SOURCES) build.sbt Makefile | $(BUILD_DIR)
	sbt "runMain $(PACKAGE).$(CLASS) --target-dir $(BUILD_DIR)"
	test -f "$@"
	touch "$@"

$(BUILD_DIR):
	mkdir -p "$@"
