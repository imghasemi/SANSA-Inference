#!/usr/bin/env bash
scp -r sansa-inference-spark_2.11-0.4.1-SNAPSHOT-jar-with-dependencies.jar MohammadaliGhasemi@akswnc4.aksw.uni-leipzig.de+titan://data/home/MohammadaliGhasemi


# copy a file to hadoop HDFS
cd /usr/local/hadoop/bin
./hadoop fs -put ~/Dbpedia_en.nt /MohammadaliGhasemi/
./hadoop fs -put ~/Dbpedia_de.nt /MohammadaliGhasemi/
./hadoop fs -put ~/LinkedGeoData.nt /MohammadaliGhasemi/
./hadoop fs -put ~/bsbmtools-0.2/BSBM_2GB.nt /MohammadaliGhasemi
./hadoop fs -put ~/bsbmtools-0.2/BSBM_20GB.nt /MohammadaliGhasemi
./hadoop fs -put ~/bsbmtools-0.2/BSBM_200GB.nt /MohammadaliGhasemi