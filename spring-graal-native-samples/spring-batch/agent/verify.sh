#!/usr/bin/env bash
if [[ `cat test-output.txt | grep "spring-batch running!"` ]]; then
  exit 0
else
  exit 1
fi
