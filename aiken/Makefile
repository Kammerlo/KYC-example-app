.PHONY: help
help:
	@echo "Usage: make <target>"
	@echo
	@echo "Targets:"
	@echo "  help   -- show this help"
	@echo "  shell  -- start a nix development shell"
	@echo "  build  -- build the project"
	@echo "  copy   -- copy plutus.json to Spring Boot resources"
	@echo "  test   -- test the project"

.PHONY: shell
shell:
	nix shell github:aiken-lang/aiken#aiken

.PHONY: build
build:
	aiken build

.PHONY: copy
copy: build
	cp plutus.json ../src/main/resources/plutus.json
	@echo "Copied plutus.json to src/main/resources/plutus.json"

.PHONY: test
test:
	aiken check
