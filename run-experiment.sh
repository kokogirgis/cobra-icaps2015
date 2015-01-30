#!/bin/bash

# compile the both the experimental framework
mvn package

# copy the resulting binaries to the experiment folder
cp target/map4rt-1.0-SNAPSHOT-jar-with-dependencies.jar experiment/solver.jar

cd experiment

CPUS=1 # the experiment can be parallelized, no of CPU to be used for the experiment
MEM=4 # maximum memory used by one simulation run

# run the experiment in empty-hall environment
./run.sh empty-hall-r25 $CPUS data.in data.out $MEM  
./addhead.sh empty-hall-r25

# run the experiment in ubremen environment
./run.sh ubremen-r27 $CPUS data.in data.out $MEM
./addhead.sh ubremen-r27

# run the experiment in warehouse environment  
./run.sh warehouse-r25 $CPUS data.in data.out $MEM
./addhead.sh warehouse-r25

# make plots from the data
Rscript makeplots.r

echo "Done! The new plots have been generated in /plots directory."









