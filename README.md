# Pss Web Services

## Requirements

For building and running the application you need:

- [JDK 11.0.18](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html)
- [Maven 3.9.0](https://maven.apache.org)

## Pipeline Documentation

The pipeline workflow is in the .github/workflows/maven.publish.yml

The high-level architecture is [pipeline](https://drive.google.com/file/d/16nqTQUpqRb1L-nCLvKKxxdASW6YE5WSV/view?usp=sharing) 


<img width="306" alt="image" src="https://github.com/IntelliSOFT-Consulting/PSS-Insight-v2-WebServices/assets/1963527/62532501-cf10-4e78-903f-7b5dca998ed7">

# Microservice Architecture
The system is developed into 2 microservices:
- InternationalInstance
- NationalInstance

# Containerisation
The system uses docker in its deployment.
The images are build using docker buildx command. This enables the build to build images for the different OS

```
docker buildx create --use --name workflowBuilder
docker buildx build --platform=linux/amd64,linux/arm64 -t ${{ env.IMAGE_NAME_INTERNATIONAL }} --push .
```
The first command creates a new builder instance for Docker buildx
The second command allows the user to specify the different platforms. 

It also tags the image with the provided name
and pushes it to dockerhub.

---

## Provenance & License

This repository is maintained by **Ascend Systems for Health** as a derivative
of **PSS Insight v2.0**, originally developed by **IntelliSOFT Consulting** under
the **USAID MTaPS Program** (implemented by Management Sciences for Health), with
the indicator framework developed under the preceding USAID SIAPS Program. The
software is built on [DHIS2](https://dhis2.org).

This software is distributed under the **GNU General Public License v3.0
(GPL-3.0)**. Modifications distributed by Ascend Systems for Health are likewise
licensed under GPL-3.0, and source is made available in accordance with the
license. See the [LICENSE](./LICENSE) and [NOTICE](./NOTICE) files for details.

*Ascend Systems for Health · https://ascendsystemsforhealth.com*










