# Day-to-day commands for the Java edition. (The Python repository's Makefile only built
# its MkDocs course site, which is not part of this repository.) Needs GNU make, Java 21
# and Node 24; Maven comes with the wrapper.

JAR      := backend/target/load-tester.jar
MOCK_JAR := mock-target/target/mock-target-1.0.0-exec.jar

.PHONY: help build verify test backend worker mock frontend frontend-build lint perf gate docker-up docker-down clean

help:            ## List the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed 's/:.*## /\t/'

build:           ## Build both jars (no tests)
	./mvnw -B -q package -DskipTests

verify:          ## Unit, MockMvc and integration tests + JaCoCo 80% gate
	./mvnw -B verify

test:            ## Unit and MockMvc tests only
	./mvnw -B test

backend: build   ## Run the API on :8080
	java -jar $(JAR)

worker: build    ## Run the queue-mode worker (needs SQS_QUEUE_URL and AWS credentials)
	java -jar $(JAR) worker

mock: build      ## Run the mock target on :8000
	java -jar $(MOCK_JAR)

frontend:        ## Vite dev server on :5173
	cd frontend && npm ci && npm run dev

frontend-build:  ## Production build of the React app
	cd frontend && npm ci && npm run build

lint:            ## ESLint on the React app
	cd frontend && npm run lint

perf: build      ## The CI performance gate (backend and mock must be running)
	java -jar $(JAR) run --config loadtest.ci.yml

gate: build      ## Regression gate against the target's baseline (backend and mock must be running)
	java -jar $(JAR) gate --api http://localhost:8080 --url http://127.0.0.1:8000/api/login \
		--sample scripts/gate_sample.json --i-own-target

docker-up:       ## Whole stack in containers, app on http://localhost:8080
	docker compose up --build

docker-down:     ## Stop the containers
	docker compose down

clean:           ## Remove build output
	./mvnw -B -q clean
	rm -rf frontend/dist
