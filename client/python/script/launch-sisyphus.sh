#!/usr/bin/env bash

NAME=$1
FULL_NAME=sisyphus-$NAME
PROJECT=allen-discovery-center-mcovert
METADATA=$2

FULL_METADATA="base-name=$NAME"

if [ ! -z "$METADATA" ]
then
    FULL_METADATA=$FULL_METADATA,$METADATA
fi

      --custom-cpu=6 \
      --custom-memory=32 \

gcloud compute \
       --project=$PROJECT \
       instances create $FULL_NAME \
       --zone=us-west1-b \
       --machine-type=n1-standard-2 \
       --subnet=default \
       --network-tier=PREMIUM \
       --maintenance-policy=MIGRATE \
       --service-account=441871726775-compute@developer.gserviceaccount.com \
       --scopes=https://www.googleapis.com/auth/devstorage.read_only,https://www.googleapis.com/auth/logging.write,https://www.googleapis.com/auth/monitoring.write,https://www.googleapis.com/auth/servicecontrol,https://www.googleapis.com/auth/service.management.readonly,https://www.googleapis.com/auth/trace.append \
       --image-family=sisyphus-worker \
       --image-project=$PROJECT \
       --boot-disk-size=200GB \
       --boot-disk-type=pd-standard \
       --boot-disk-device-name=$FULL_NAME \
       --description='sisyphus worker' \
       --metadata=$FULL_METADATA
