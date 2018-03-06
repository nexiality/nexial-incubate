#!/bin/bash

# --------------------------------------------------------------------------------
# environment variable guide
# --------------------------------------------------------------------------------
# JAVA_HOME           - home directory of a valid JDK installation (1.6 or above)
# NEXIAL_OUT          - the output directory
# --------------------------------------------------------------------------------

function resolveOSXAppPath() {
	local appExec=$1
	path=`locate "MacOS/${appExec}" | grep -E "${appExec}$"`
	echo ${path}
}

function resolveLinuxAppPath() {
	location=`which --read-alias $1`
	if [[ "$?" = "0" ]] ; then
		echo ${location}
	else
		echo ""
	fi
}

# utilities to be invoked by other frontend scripts
PROJECT_BASE=~/projects
NEXIAL_HOME=$(cd `dirname $0`/..; pwd -P)
NEXIAL_LIB=${NEXIAL_HOME}/lib
NEXIAL_CLASSES=${NEXIAL_HOME}/classes

DEFAULT_CHROME_BIN=
if [[ "`uname -s`" = "Darwin" ]] ; then
	DEFAULT_CHROME_BIN="`resolveOSXAppPath "Google Chrome"`"
else
	DEFAULT_CHROME_BIN="`resolveLinuxAppPath google-chrome`"
fi

if [[ -z "${DEFAULT_CHROME_BIN// }" ]] ; then
	echo "Unable to resolve location of Google Chrome.  If you want to use Chrome, you will need to set its location via the CHROME_BIN environment variable"
	DEFAULT_CHROME_BIN=
fi

if [[ "`uname -s`" = "Darwin" ]] ; then
	DEFAULT_FIREFOX_BIN="`resolveOSXAppPath firefox-bin`"
else
	DEFAULT_FIREFOX_BIN="`resolveLinuxAppPath firefox-bin`"
fi

if [[ -z "${DEFAULT_FIREFOX_BIN// }" ]] ; then
	echo "Unable to resolve location of Firefox.  If you want to use Firefox, you will need to set its location via the FIREFOX_BIN environment variable"
	DEFAULT_FIREFOX_BIN=
fi

# --------------------------------------------------------------------------------
# setting Java runtime options and classpath
# --------------------------------------------------------------------------------
JAVA_OPT="${JAVA_OPT} -Xms256m"
JAVA_OPT="${JAVA_OPT} -Xmx1024m"
JAVA_OPT="${JAVA_OPT} -ea"
JAVA_OPT="${JAVA_OPT} -Dfile.encoding=UTF-8"
JAVA_OPT="${JAVA_OPT} -Dnexial.home=${NEXIAL_HOME}"
JAVA_OPT="${JAVA_OPT} -Dwebdriver.winium.verbose=true"
JAVA_OPT="${JAVA_OPT} -Dwebdriver.winium.silent=false"

# --------------------------------------------------------------------------------
# Make sure prerequisite environment variables are set
# --------------------------------------------------------------------------------
function checkJava() {
	if [ "${JAVA_HOME}" = "" -a "${JRE_HOME}" = "" ]; then
		echo ERROR!!!
		echo Neither the JAVA_HOME nor the JRE_HOME environment variable is defined
		echo At least one of these environment variables is needed to run this program
		echo
		exit -1
	fi

	if [ -x ${JAVA_HOME}/bin/java ]; then
		JAVA=${JAVA_HOME}/bin/java
	else
		if [ -x ${JRE_HOME}/bin/java ]; then
			JAVA=${JRE_HOME}/bin/java
		else
			echo ERROR!!!
			echo The JAVA_HOME environment variable is not defined correctly
			echo This environment variable is needed to run this program
			echo NB: JAVA_HOME should point to a JDK not a JRE
			echo
			exit -1
		fi
	fi
}


function title() {
	title="${1}"
	title_length=${#title}
	space_length=$((80 - 4 - ${title_length} - 3))

	echo
	echo "--------------------------------------------------------------------------------"
	echo "|                        nexial - test automation for all                      |"
	echo "--------------------------------------------------------------------------------"
	printf "[:: "
	printf "${title}"
	printf "%0.s " $(seq 1 ${space_length})
	printf "::]"
	echo
	echo "--------------------------------------------------------------------------------"
}


function resolveEnv() {
	JAVA_VERSION=`echo "$(${JAVA} -version 2>&1)" | grep "java version" | awk '{ print substr($3, 2, length($3)-2); }'`

	echo "» ENVIRONMENT: "
	echo "  CURRENT TIME:   `date \"+%Y-%m-%d %H:%M%:%S\"`"
	echo "  CURRENT USER:   ${USER}"
	echo "  CURRENT HOST:   `hostname`"
	echo "  JAVA:           ${JAVA}"
	echo "  JAVA VERSION:   ${JAVA_VERSION}"
	echo "  NEXIAL_HOME:    ${NEXIAL_HOME}"
	echo "  NEXIAL_LIB:     ${NEXIAL_LIB}"
	echo "  NEXIAL_CLASSES: ${NEXIAL_CLASSES}"
	echo "  PROJECT_BASE:   ${PROJECT_BASE}"
	if [ "${PROJECT_HOME}" != "" ]; then
		echo "  PROJECT_HOME:   ${PROJECT_HOME}"
	fi
}
