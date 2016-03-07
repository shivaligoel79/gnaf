# gnaf

Load [G-NAF data set](http://www.data.gov.au/dataset/geocoded-national-address-file-g-naf) into a database and [Elasticsearch](https://www.elastic.co/).

## Install Tools

To run the code install:
- a JRE e.g. from openjdk-7 or 8;
- the build tool [sbt](http://www.scala-sbt.org/).

To develop [Scala](http://scala-lang.org/) web services install:
- the above items (you may prefer to install the full JDK instead of just the JRE but I think the JRE is sufficient);
- the [Scala IDE](http://scala-ide.org/download/current.html).

Run `sbt update-classifiers` to download dependencies including the H2 database engine used in the next section.

## Create Database

The scripts described here automate the procedure described in the [getting started guide](https://www.psma.com.au/sites/default/files/g-naf_-_getting_started_guide.pdf).
See also https://github.com/minus34/gnaf-loader as an alternative which makes some updates to the data.

### Download data, Unpack & Generate SQL
Running:

	src/main/script/createGnafDb.sh

- downloads the G-NAF zip file to `data/` (if not found);
- unzips to `data/unzipped/` (if not found); and
- writes SQL to create the H2 database to `data/createGnafDb.sql` (`createGnafDb.sh` may require adaptation for other databases).

### Start Database Engine & SQL Client
The H2 database engine is started with:

	java -jar ~/.ivy2/cache/com.h2database/h2/jars/h2-1.4.191.jar

(the H2 jar file was put here by `sbt update-classifiers`, alternatively download the jar from the H2 web site and run it as above).
This:
- starts a web server on port 8082 serving the SQL client application, it should also open http://127.0.1.1:8082/login.jsp in a web browser;
- starts a tcp/jdbc server on port 9092; and
- starts a postgres protocol server on port 5435 (note this is different from the default port used by Postgres).

The database engine is stopped with `Ctrl-C` (but not yet as it's needed for the next step).

### Run SQL
In the SQL client, enter JDBC URL: `jdbc:h2:file:~/sw/gnaf/data/gnaf`, User name: `gnaf` and Password: `gnaf`) and click `Connect` to create an empty database at this location.
This is a single file, zero-admin database. It can me moved/renamed simply by moving/renaming the `gnaf.mv.db` file.


Run the SQL commands either by:
- entering: `RUNSCRIPT FROM '~/sw/gnaf/data/createGnafDb.sql'` into the SQL input area (this method displays no indication of progress); or
- pasting the content of this file into the SQL input area (this method displays what is going on).

On a macbook-pro (with SSD) it takes 26 min to load the data and another 53 min to create the indexes. 

## Example Queries
Create a read only user:

    CREATE USER READONLY PASSWORD 'READONLY'
    GRANT SELECT ON SCHEMA PUBLIC TO READONLY

Find me (fast):

    SELECT SL.*, AD.*
    FROM
        STREET_LOCALITY SL
        LEFT JOIN ADDRESS_DETAIL AD ON AD.STREET_LOCALITY_PID = SL.STREET_LOCALITY_PID  
    WHERE SL.STREET_NAME = 'TYTHERLEIGH'
        AND AD.NUMBER_FIRST = 14

This is slow (45892 ms):

    SELECT * FROM ADDRESS_VIEW 
    WHERE STREET_NAME = 'TYTHERLEIGH'
    AND NUMBER_FIRST = 14

but at least this is fast:

    SELECT * FROM ADDRESS_VIEW 
    WHERE ADDRESS_DETAIL_PID = 'GAACT714928273'

This shows some dodgy STREET_LOCALITY_ALIAS records:

	SELECT sl.STREET_NAME, sl.STREET_TYPE_CODE, sl.STREET_SUFFIX_CODE,
	  sla.STREET_NAME, sla.STREET_TYPE_CODE , sla.STREET_SUFFIX_CODE 
	FROM STREET_LOCALITY_ALIAS sla, STREET_LOCALITY sl
	WHERE sla.STREET_LOCALITY_PID = sl.STREET_LOCALITY_PID
	AND sl.STREET_NAME = 'REED'
	
	STREET_NAME     STREET_TYPE_CODE    STREET_SUFFIX_CODE      STREET_NAME     STREET_TYPE_CODE    STREET_SUFFIX_CODE  
	REED            STREET              S                       REED STREET     SOUTH               null
	REED            STREET              N                       REED STREET     NORTH               null

## Generate Slick bindings
[Slick](http://slick.typesafe.com/) provides "Functional Relational Mapping for Scala".
To generate Slick mappings for the database (first disconnect any other clients):

    mkdir -p generated
    sbt
    > console
    slick.codegen.SourceCodeGenerator.main(
        Array("slick.driver.H2Driver", "org.h2.Driver", "jdbc:h2:file:~/sw/gnaf/data/gnaf", "generated", "au.com.data61.gnaf.db")
    )

This generates code in the `generated/au/com/data61/db` directory.
At this stage its not clear whether the mapping will:
1. need hand tweaks (i.e. once off generation then its part of the source code); or
2. not need hand tweaks and should be generated by the build and is not part of our source code (better if schema changes much/often).

For now we'll opt for 1 and move this into `src/main/scala`.
There has been an unsuccessful attempt at using (sbt-slick-codegen)[https://github.com/tototoshi/sbt-slick-codegen]
for option 2 (see comments in `project/plugins.sbt`).

## Build

	sbt oneJar
	
builds the uber-jar `target/scala-2.11/gnaf_2.11-0.1-SNAPSHOT-one-jar.jar` containing all dependencies.

## Run

	$ time java -Xmx3G -jar target/scala-2.11/gnaf_2.11-0.1-SNAPSHOT-one-jar.jar > out
	real    40m36.031s
	user    71m37.444s
	sys     25m02.920s

Before adding the geocode location it was much faster:
	
	real   24m52.579s
	user   47m27.900s
	sys    14m30.308s
	
writes one line of JSON to the file `out` for each GNAF `ADDRESS_DETAIL` row with CONFIDENCE > -1. Logging is written to gnaf.log.

If an H2 result set contains more than
[MAX_MEMORY_ROWS](http://www.h2database.com/html/grammar.html?highlight=max_memory_rows&search=MAX_MEMORY_ROWS#set_max_memory_rows),
it is spooled to disk before the first row is provided to the client.
The default is 40000 per GB of available RAM and setting a non-default value requires database admin rights (which we prefer to avoid using).
Analysis in comments in `Main.scala` show that we need to handle result sets up to 95,004 rows, so allocating up to 3GB of heap should avoid spooling.

## Elastic Search

### Indexing
The above file is transformed into Elastic Search's [bulk data format](https://www.elastic.co/guide/en/elasticsearch/guide/current/bulk.html)
using the JSON transformation tool [jq](https://stedolan.github.io/jq/):

	$ time jq -c '
	{ index: { _index: "gnaf", _type: "gnaf", _id: .addressDetailPid } },
	. ' out > bulk
	real   9m3.276s
	user   8m35.252s
	sys    0m13.560s

An old index is deleted with:

	curl -XDELETE 'localhost:9200/gnaf/'
	
The index is created with a custom mapping:

	curl -XPUT 'localhost:9200/gnaf/' --data-binary @src/main/resources/gnafMapping.json
	
The data is split into chunks and sent for indexing with:

	split -l10000 -a3 bulk
	for i in x*
	do
	  echo $i
	  curl -s -XPOST localhost:9200/_bulk --data-binary @$i
	done
	

### Searching

Search for an exact match:

	$ curl -XPOST 'localhost:9200/gnaf/_search?pretty' -d '
	{
	  "query": { "match": { "street.name": "CURRONG" } },
	  "size": 5
	}' 

Search for a fuzzy match (use `_all` instead of `street.name` to search all fields):
	
	$ curl -XPOST 'localhost:9200/gnafdummy/_search?pretty' -d '
	{
	  "query": { "match": { "street.name": { "query": "CURRONGT",  "fuzziness": 2, "prefix_length": 2 } } },
	  "size": 5
	}' 

## Data License

Incorporates or developed using G-NAF ©PSMA Australia Limited licensed by the Commonwealth of Australia under the
[http://data.gov.au/dataset/19432f89-dc3a-4ef3-b943-5326ef1dbecc/resource/09f74802-08b1-4214-a6ea-3591b2753d30/download/20160226---EULA---Open-G-NAF.pdf](Open Geo-coded National Address File (G-NAF) End User Licence Agreement).

## To Do
Add some pointers to H2 doco showing how to start a H2 with a Postgres protocol listener and connect to it with psql Postgres client. That may be a more convenient way to run `createGnafDb.sql`. Note psql cannot connect with blank username and password, so you need to create a user and grant it suitable rights.

Add (in-memory) lookup from flatTypeCode CODE -> FLAT_TYPE_AUT.NAME
UNIT -> UNIT
BTSD -> BOATSHED (only one word so whitespace not really needed, but use it anyway e.g. needed for following case:
and streetSuffixCode CODE -> STREET_SUFFIX_AUT.NAME
DE -> DEVIATION
EX -> EXTENSION
NE -> NORTH EAST