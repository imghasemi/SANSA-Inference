scp -r sansa-inference-spark_2.11-0.4.1-SNAPSHOT-jar-with-dependencies.jar MohammadaliGhasemi@akswnc4.aksw.uni-leipzig.de+titan://data/home/MohammadaliGhasemi


# copy a file to hadoop HDFS
cd /usr/local/hadoop/bin
./hadoop fs -put ~/Dbpedia_en.nt /MohammadaliGhasemi/
./hadoop fs -put ~/Dbpedia_de.nt /MohammadaliGhasemi/
./hadoop fs -put ~/LinkedGeoData.nt /MohammadaliGhasemi/
