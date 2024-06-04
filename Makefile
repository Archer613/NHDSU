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