idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init --recursive 

reformat:
	mill -i __.reformat

compile:
	mill -i NHDSU.compile

verilog:
	mill -i NHDSU.test.runMain NHDSU.DSU -td build

test-top-chil2-onecore-1ul:
	mill -i NHDSU.test.runMain NHDSU.TestTop_CHIL2_OneCore_1UL -td build

test-top-chil2-dualcore-1ul:
	mill -i NHDSU.test.runMain NHDSU.TestTop_CHIL2_DualCore_1UL -td build