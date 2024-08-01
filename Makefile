idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init --recursive 

reformat:
	mill -i __.reformat

compile:
	mill -i DONGJIANG.compile

test-top-chil2-onecore-1ul:
	mill -i DONGJIANG.test.runMain DONGJIANG.TestTop_CHIL2_OneCore_1UL -td build

test-top-chil2-dualcore-1ul:
	mill -i DONGJIANG.test.runMain DONGJIANG.TestTop_CHIL2_DualCore_1UL -td build

test-top-nhl2-onecore-1ul:
	mill -i DONGJIANG.test.runMain DONGJIANG.TestTop_NHL2_OneCore_1UL -td build

test-top-nhl2-dualcore-1ul:
	mill -i DONGJIANG.test.runMain DONGJIANG.TestTop_NHL2_DualCore_1UL -td build