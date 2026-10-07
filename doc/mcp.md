# Fume's MCP server

Fume's daemon is a [Model Context Protocol](https://modelcontextprotocol.io/) server, through
which an agent asks about runs of tests and benchmarks: which runs there have been, how each
test fared, why a test failed, what a benchmark measured, and what the daemon is doing now. It
answers in JSON, against the schema it publishes, and every answer is derived from the same
record and event stream fume's own terminal report and dashboard are.

## Connecting

The server speaks MCP's streamable HTTP transport, at the path `/mcp`:

 - `fume mcp` serves it at `http://localhost:8092/mcp` until Ctrl+C; `--mcp-port`, or `mcp-port`
   in a configuration file, changes the port;
 - a bare `mcp` keyword in `.pyrocosm/fume/config.tel` or `~/.config/fume/config.tel` serves it
   from the moment the daemon starts, for as long as it lives, so an agent always finds it;
 - the dashboard (`fume serve`, or `serve` in a configuration file) mounts it at `/mcp` on its
   own port too, `http://localhost:8090/mcp` by default.

Claude Code connects with:

```sh
claude mcp add --transport http fume http://localhost:8092/mcp
```

Any client speaking streamable HTTP connects the same way. `fume quit` stops the server with the
daemon.

## Tools

No tool takes an optional parameter: every parameter listed is required, and a tool with a
sensible default is a tool of its own (synesthesia cannot yet decode an absent parameter:
[propensive/soundness#2187](https://github.com/propensive/soundness/issues/2187)). Wherever a run id is taken, `last` names the newest run.
A tool that cannot answer — an unknown run or suite — fails with a message saying why.

| Tool | Parameters | Answers with |
|---|---|---|
| `runs` | `limit` | `RunSummary[]`: the most recent runs, newest first, runs in flight first |
| `runsIn` | `workspace`, `limit` | `RunSummary[]`: the runs invoked from a working directory under `workspace` |
| `run` | `id` | `RunDetail`: one run, with its classpath, selection, suites, and whether results are available |
| `results` | `run` | `TestResult[]`: every test of the run, with its measurements |
| `suiteResults` | `run`, `suite` | `TestResult[]`: the tests of one suite, by class name |
| `test` | `run`, `test` | `TestResult[]`: one test, by its 6-hex id, moniker, or slash-joined path |
| `benchmarks` | `run` | `TestResult[]`: the benchmarks, stress tests and profiles, with their records |
| `failures` | `run` | `Failures`: each failing test with its message, stack trace, captured values and expected-against-found comparison, plus errors that ended a suite |
| `captured` | `run`, `suite` | text: what the suite printed on stdout and stderr outside its report |
| `processes` | | `Processes`: the daemon, its services, the runs in flight with the tests they are executing, and any run it is working for another machine |
| `suites` | `classpath` | `IndexedSuite[]`: the suites a classpath declares, from its index, running nothing |
| `tests` | `classpath`, `terms` | `IndexedTest[]`: the tests a classpath declares, narrowed by space-separated selection terms as `fume list` takes them |

A `classpath` is `:`-separated jars and directories, absolute, with globs expanded as
`--classpath` expands them. A `TestResult` carries a `rerun` command which runs exactly that
test.

## Resources

| URI | Content |
|---|---|
| `fume://schema` | the JSON Schema of every type below, by name, derived from the types themselves |
| `fume://docs` | this document |
| `fume://runs/latest` | the newest run as `RunDetail`, as `run` answers for `last` |

## Vocabularies

Every vocabulary is a text whose words are Probably's own:

 - a test's `kind` is `check`, `bench`, `stress` or `profile`;
 - a cell's `outcome` is `pass`, `fail`, `throws`, `check-throws`, `aspire-pass` or `aspire-fail`;
 - a test's `status` is its collective outcome: one of those, `mixed` when its cells disagree,
   or `bench`, `stress`, `profile` for a measurement;
 - a run's `outcome` is `passed`, `failed` or `aborted`, and absent while the run is in flight;
 - a run's `invoker` is `human`, `claude`, `codex`, or `remote` for a run another machine sent;
 - a run's `results` are `available`, `partial` (some suites could not stream their events),
   `incompatible` (the suites were built against a Probably whose event schema differs from
   this fume's) or `none`.

Instants are ISO 8601 in UTC; durations are in the unit each field names.

## The run directory

Every run is persisted under `$XDG_STATE_HOME/fume/runs/<id>/` (`~/.local/state/fume/runs/`):

```
run.tel              the run's record, rewritten as the run progresses
events/<n>.bintel    suite n's event frames, verbatim, its schema fingerprint first
captured/<n>.txt     what suite n printed outside its report
```

Suites are numbered in the order the run scheduled them. A run's id is the UTC time it started
and four hex digits of entropy, `20261007-143512-3f9a`, so ids sort by time and are stable
across daemons. The `retention` setting (200 by default) is how many runs are kept. Results are
replayed from the frames when asked for, through the same model a live run folds into; a run in
flight is answered from its live model.

## Schema

The schema `fume://schema` serves, for fume's version at the time of writing:

```json
{
  "description": "The types fume 0.8.0 answers MCP tool calls with, by name",
  "properties": {
    "Frame": {
      "properties": {
        "className": {
          "description": "the frame's class",
          "optional": false,
          "type": "string"
        },
        "method": {
          "description": "the frame's method",
          "optional": false,
          "type": "string"
        },
        "file": {
          "description": "the frame's source file",
          "optional": false,
          "type": "string"
        },
        "line": {
          "description": "the frame's line, when known",
          "optional": true,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "className",
        "method",
        "file"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "TraceComponent": {
      "properties": {
        "className": {
          "description": "the exception's class",
          "optional": false,
          "type": "string"
        },
        "message": {
          "description": "the exception's message",
          "optional": false,
          "type": "string"
        },
        "frames": {
          "description": "the stack frames, outermost first",
          "items": {
            "properties": {
              "className": {
                "description": "the frame's class",
                "optional": false,
                "type": "string"
              },
              "method": {
                "description": "the frame's method",
                "optional": false,
                "type": "string"
              },
              "file": {
                "description": "the frame's source file",
                "optional": false,
                "type": "string"
              },
              "line": {
                "description": "the frame's line, when known",
                "optional": true,
                "type": "integer"
              }
            },
            "optional": false,
            "required": [
              "className",
              "method",
              "file"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        }
      },
      "optional": false,
      "required": [
        "className",
        "message",
        "frames"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Trace": {
      "properties": {
        "components": {
          "description": "the chain of causes, the thrown exception first",
          "items": {
            "properties": {
              "className": {
                "description": "the exception's class",
                "optional": false,
                "type": "string"
              },
              "message": {
                "description": "the exception's message",
                "optional": false,
                "type": "string"
              },
              "frames": {
                "description": "the stack frames, outermost first",
                "items": {
                  "properties": {
                    "className": {
                      "description": "the frame's class",
                      "optional": false,
                      "type": "string"
                    },
                    "method": {
                      "description": "the frame's method",
                      "optional": false,
                      "type": "string"
                    },
                    "file": {
                      "description": "the frame's source file",
                      "optional": false,
                      "type": "string"
                    },
                    "line": {
                      "description": "the frame's line, when known",
                      "optional": true,
                      "type": "integer"
                    }
                  },
                  "optional": false,
                  "required": [
                    "className",
                    "method",
                    "file"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              }
            },
            "optional": false,
            "required": [
              "className",
              "message",
              "frames"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        }
      },
      "optional": false,
      "required": [
        "components"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Processes": {
      "properties": {
        "daemon": {
          "description": "this daemon",
          "properties": {
            "pid": {
              "description": "the daemon's pid",
              "optional": false,
              "type": "integer"
            },
            "version": {
              "description": "fume's version",
              "optional": false,
              "type": "string"
            },
            "started": {
              "description": "when the daemon started",
              "format": "date-time",
              "optional": false,
              "type": "string"
            },
            "runsDirectory": {
              "description": "where runs are persisted",
              "optional": false,
              "type": "string"
            }
          },
          "optional": false,
          "required": [
            "pid",
            "version",
            "started",
            "runsDirectory"
          ],
          "additionalProperties": false,
          "type": "object"
        },
        "services": {
          "description": "the services this daemon is serving",
          "items": {
            "properties": {
              "name": {
                "description": "`dashboard`, `listener` or `mcp`",
                "optional": false,
                "type": "string"
              },
              "port": {
                "description": "the port it serves on",
                "optional": false,
                "type": "integer"
              }
            },
            "optional": false,
            "required": [
              "name",
              "port"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "runs": {
          "description": "runs in flight",
          "items": {
            "properties": {
              "suitesDone": {
                "description": "suites finished so far",
                "optional": false,
                "type": "integer"
              },
              "forked": {
                "description": "the pid of the suite's own JVM, under `--fork`",
                "optional": true,
                "type": "integer"
              },
              "suitesTotal": {
                "description": "suites scheduled",
                "optional": false,
                "type": "integer"
              },
              "current": {
                "description": "the suite running now",
                "optional": true,
                "type": "string"
              },
              "summary": {
                "description": "the run in summary",
                "properties": {
                  "duration": {
                    "description": "the run's length in milliseconds, once finished",
                    "optional": true,
                    "type": "integer"
                  },
                  "suites": {
                    "description": "how many suites the run scheduled",
                    "optional": false,
                    "type": "integer"
                  },
                  "invoker": {
                    "description": "who launched the run: `human`, `claude`, `codex`, or `remote` for another fume",
                    "optional": false,
                    "type": "string"
                  },
                  "finished": {
                    "description": "when the run finished; absent while it is in flight",
                    "format": "date-time",
                    "optional": true,
                    "type": "string"
                  },
                  "outcome": {
                    "description": "`passed`, `failed` or `aborted`; absent while the run is in flight",
                    "optional": true,
                    "type": "string"
                  },
                  "id": {
                    "description": "the run's id, stable across daemon restarts; `last` names the newest run in any tool",
                    "optional": false,
                    "type": "string"
                  },
                  "machine": {
                    "description": "the machine the suites ran on",
                    "optional": false,
                    "type": "string"
                  },
                  "workspace": {
                    "description": "the working directory `fume` was invoked from",
                    "optional": false,
                    "type": "string"
                  },
                  "selection": {
                    "description": "the selection terms forwarded to each suite",
                    "items": {
                      "optional": false,
                      "type": "string"
                    },
                    "optional": false,
                    "type": "array"
                  },
                  "started": {
                    "description": "when the run started, ISO 8601 in UTC",
                    "format": "date-time",
                    "optional": false,
                    "type": "string"
                  },
                  "totals": {
                    "description": "the run's totals, once finished",
                    "properties": {
                      "passed": {
                        "description": "tests which passed, including measurements which completed",
                        "optional": false,
                        "type": "integer"
                      },
                      "failed": {
                        "description": "tests which failed, threw, or had a failing check",
                        "optional": false,
                        "type": "integer"
                      },
                      "aspirePassed": {
                        "description": "aspirational tests which passed",
                        "optional": false,
                        "type": "integer"
                      },
                      "aspireFailed": {
                        "description": "aspirational tests which failed, which does not fail the run",
                        "optional": false,
                        "type": "integer"
                      }
                    },
                    "optional": true,
                    "required": [
                      "passed",
                      "failed",
                      "aspirePassed",
                      "aspireFailed"
                    ],
                    "additionalProperties": false,
                    "type": "object"
                  }
                },
                "optional": false,
                "required": [
                  "id",
                  "workspace",
                  "invoker",
                  "machine",
                  "started",
                  "selection",
                  "suites"
                ],
                "additionalProperties": false,
                "type": "object"
              },
              "active": {
                "description": "tests and suites in flight",
                "items": {
                  "properties": {
                    "ref": {
                      "description": "the test or suite in flight",
                      "properties": {
                        "name": {
                          "description": "the test's name",
                          "optional": false,
                          "type": "string"
                        },
                        "path": {
                          "description": "the path of names or monikers from the root suite to the test",
                          "items": {
                            "optional": false,
                            "type": "string"
                          },
                          "optional": false,
                          "type": "array"
                        },
                        "line": {
                          "description": "the line declaring the test",
                          "optional": false,
                          "type": "integer"
                        },
                        "moniker": {
                          "description": "the test's moniker, a selection term, when it declares one",
                          "optional": true,
                          "type": "string"
                        },
                        "id": {
                          "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
                          "optional": false,
                          "type": "string"
                        },
                        "file": {
                          "description": "the source file declaring the test",
                          "optional": false,
                          "type": "string"
                        },
                        "suite": {
                          "description": "the suite class the test was run under",
                          "optional": false,
                          "type": "string"
                        }
                      },
                      "optional": false,
                      "required": [
                        "id",
                        "name",
                        "path",
                        "suite",
                        "file",
                        "line"
                      ],
                      "additionalProperties": false,
                      "type": "object"
                    }
                  },
                  "optional": false,
                  "required": [
                    "ref"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              }
            },
            "optional": false,
            "required": [
              "summary",
              "suitesDone",
              "suitesTotal",
              "active"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "worker": {
          "description": "the run this daemon is working for another machine, if any",
          "properties": {
            "controller": {
              "description": "the hostname of the controller whose run this daemon is working",
              "optional": false,
              "type": "string"
            },
            "current": {
              "description": "the suite running now",
              "optional": true,
              "type": "string"
            }
          },
          "optional": true,
          "required": [
            "controller"
          ],
          "additionalProperties": false,
          "type": "object"
        }
      },
      "optional": false,
      "required": [
        "daemon",
        "services",
        "runs"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Fatal": {
      "properties": {
        "trace": {
          "description": "the error which ended a suite",
          "properties": {
            "components": {
              "description": "the chain of causes, the thrown exception first",
              "items": {
                "properties": {
                  "className": {
                    "description": "the exception's class",
                    "optional": false,
                    "type": "string"
                  },
                  "message": {
                    "description": "the exception's message",
                    "optional": false,
                    "type": "string"
                  },
                  "frames": {
                    "description": "the stack frames, outermost first",
                    "items": {
                      "properties": {
                        "className": {
                          "description": "the frame's class",
                          "optional": false,
                          "type": "string"
                        },
                        "method": {
                          "description": "the frame's method",
                          "optional": false,
                          "type": "string"
                        },
                        "file": {
                          "description": "the frame's source file",
                          "optional": false,
                          "type": "string"
                        },
                        "line": {
                          "description": "the frame's line, when known",
                          "optional": true,
                          "type": "integer"
                        }
                      },
                      "optional": false,
                      "required": [
                        "className",
                        "method",
                        "file"
                      ],
                      "additionalProperties": false,
                      "type": "object"
                    },
                    "optional": false,
                    "type": "array"
                  }
                },
                "optional": false,
                "required": [
                  "className",
                  "message",
                  "frames"
                ],
                "additionalProperties": false,
                "type": "object"
              },
              "optional": false,
              "type": "array"
            }
          },
          "optional": false,
          "required": [
            "components"
          ],
          "additionalProperties": false,
          "type": "object"
        },
        "affected": {
          "description": "the tests that were running when it did",
          "items": {
            "properties": {
              "name": {
                "description": "the test's name",
                "optional": false,
                "type": "string"
              },
              "path": {
                "description": "the path of names or monikers from the root suite to the test",
                "items": {
                  "optional": false,
                  "type": "string"
                },
                "optional": false,
                "type": "array"
              },
              "line": {
                "description": "the line declaring the test",
                "optional": false,
                "type": "integer"
              },
              "moniker": {
                "description": "the test's moniker, a selection term, when it declares one",
                "optional": true,
                "type": "string"
              },
              "id": {
                "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
                "optional": false,
                "type": "string"
              },
              "file": {
                "description": "the source file declaring the test",
                "optional": false,
                "type": "string"
              },
              "suite": {
                "description": "the suite class the test was run under",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "id",
              "name",
              "path",
              "suite",
              "file",
              "line"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        }
      },
      "optional": false,
      "required": [
        "trace",
        "affected"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "RunSummary": {
      "properties": {
        "duration": {
          "description": "the run's length in milliseconds, once finished",
          "optional": true,
          "type": "integer"
        },
        "suites": {
          "description": "how many suites the run scheduled",
          "optional": false,
          "type": "integer"
        },
        "invoker": {
          "description": "who launched the run: `human`, `claude`, `codex`, or `remote` for another fume",
          "optional": false,
          "type": "string"
        },
        "finished": {
          "description": "when the run finished; absent while it is in flight",
          "format": "date-time",
          "optional": true,
          "type": "string"
        },
        "outcome": {
          "description": "`passed`, `failed` or `aborted`; absent while the run is in flight",
          "optional": true,
          "type": "string"
        },
        "id": {
          "description": "the run's id, stable across daemon restarts; `last` names the newest run in any tool",
          "optional": false,
          "type": "string"
        },
        "machine": {
          "description": "the machine the suites ran on",
          "optional": false,
          "type": "string"
        },
        "workspace": {
          "description": "the working directory `fume` was invoked from",
          "optional": false,
          "type": "string"
        },
        "selection": {
          "description": "the selection terms forwarded to each suite",
          "items": {
            "optional": false,
            "type": "string"
          },
          "optional": false,
          "type": "array"
        },
        "started": {
          "description": "when the run started, ISO 8601 in UTC",
          "format": "date-time",
          "optional": false,
          "type": "string"
        },
        "totals": {
          "description": "the run's totals, once finished",
          "properties": {
            "passed": {
              "description": "tests which passed, including measurements which completed",
              "optional": false,
              "type": "integer"
            },
            "failed": {
              "description": "tests which failed, threw, or had a failing check",
              "optional": false,
              "type": "integer"
            },
            "aspirePassed": {
              "description": "aspirational tests which passed",
              "optional": false,
              "type": "integer"
            },
            "aspireFailed": {
              "description": "aspirational tests which failed, which does not fail the run",
              "optional": false,
              "type": "integer"
            }
          },
          "optional": true,
          "required": [
            "passed",
            "failed",
            "aspirePassed",
            "aspireFailed"
          ],
          "additionalProperties": false,
          "type": "object"
        }
      },
      "optional": false,
      "required": [
        "id",
        "workspace",
        "invoker",
        "machine",
        "started",
        "selection",
        "suites"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Capture": {
      "properties": {
        "label": {
          "description": "the captured value's label",
          "optional": false,
          "type": "string"
        },
        "value": {
          "description": "the captured value, rendered",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "label",
        "value"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Completion": {
      "properties": {
        "coordinates": {
          "description": "the cell's coordinates; empty for a test without axes",
          "items": {
            "properties": {
              "axis": {
                "description": "the axis's label",
                "optional": false,
                "type": "string"
              },
              "value": {
                "description": "the axis's value for this cell, as text",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "axis",
              "value"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "outcome": {
          "description": "`pass`, `fail`, `throws`, `check-throws`, `aspire-pass` or `aspire-fail`",
          "optional": false,
          "type": "string"
        },
        "nanoseconds": {
          "description": "how long the cell took, in nanoseconds",
          "optional": false,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "coordinates",
        "outcome",
        "nanoseconds"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "SuiteSummary": {
      "properties": {
        "captured": {
          "description": "whether the suite printed anything outside its report; see the `captured` tool",
          "optional": false,
          "type": "boolean"
        },
        "finished": {
          "description": "when the suite finished",
          "format": "date-time",
          "optional": false,
          "type": "string"
        },
        "passed": {
          "description": "whether the suite passed",
          "optional": false,
          "type": "boolean"
        },
        "started": {
          "description": "when the suite started",
          "format": "date-time",
          "optional": false,
          "type": "string"
        },
        "totals": {
          "description": "the suite's totals, when it streamed its events",
          "properties": {
            "passed": {
              "description": "tests which passed, including measurements which completed",
              "optional": false,
              "type": "integer"
            },
            "failed": {
              "description": "tests which failed, threw, or had a failing check",
              "optional": false,
              "type": "integer"
            },
            "aspirePassed": {
              "description": "aspirational tests which passed",
              "optional": false,
              "type": "integer"
            },
            "aspireFailed": {
              "description": "aspirational tests which failed, which does not fail the run",
              "optional": false,
              "type": "integer"
            }
          },
          "optional": true,
          "required": [
            "passed",
            "failed",
            "aspirePassed",
            "aspireFailed"
          ],
          "additionalProperties": false,
          "type": "object"
        },
        "suite": {
          "description": "the suite's class name",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "suite",
        "passed",
        "started",
        "finished",
        "captured"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Benchmark": {
      "properties": {
        "coordinates": {
          "description": "the cell's coordinates",
          "items": {
            "properties": {
              "axis": {
                "description": "the axis's label",
                "optional": false,
                "type": "string"
              },
              "value": {
                "description": "the axis's value for this cell, as text",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "axis",
              "value"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "operationRate": {
          "description": "the operation rate, when derived",
          "optional": true,
          "type": "string"
        },
        "runs": {
          "description": "measurement runs",
          "optional": false,
          "type": "integer"
        },
        "mean": {
          "description": "mean time per operation, in nanoseconds",
          "optional": false,
          "type": "number"
        },
        "min": {
          "description": "fastest run, in nanoseconds per operation",
          "optional": false,
          "type": "number"
        },
        "confidence": {
          "description": "confidence, as a percentage",
          "optional": false,
          "type": "integer"
        },
        "allocation": {
          "description": "bytes allocated per operation, when measured",
          "optional": true,
          "type": "integer"
        },
        "max": {
          "description": "slowest run, in nanoseconds per operation",
          "optional": false,
          "type": "number"
        },
        "operationSize": {
          "description": "the size of one operation, when declared",
          "optional": true,
          "type": "string"
        },
        "sd": {
          "description": "standard deviation, in nanoseconds",
          "optional": false,
          "type": "number"
        },
        "iterations": {
          "description": "operations timed",
          "optional": false,
          "type": "integer"
        },
        "nanoseconds": {
          "description": "the time measured, in nanoseconds",
          "optional": false,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "coordinates",
        "nanoseconds",
        "iterations",
        "runs",
        "mean",
        "min",
        "max",
        "sd",
        "confidence"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "TestRef": {
      "properties": {
        "name": {
          "description": "the test's name",
          "optional": false,
          "type": "string"
        },
        "path": {
          "description": "the path of names or monikers from the root suite to the test",
          "items": {
            "optional": false,
            "type": "string"
          },
          "optional": false,
          "type": "array"
        },
        "line": {
          "description": "the line declaring the test",
          "optional": false,
          "type": "integer"
        },
        "moniker": {
          "description": "the test's moniker, a selection term, when it declares one",
          "optional": true,
          "type": "string"
        },
        "id": {
          "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
          "optional": false,
          "type": "string"
        },
        "file": {
          "description": "the source file declaring the test",
          "optional": false,
          "type": "string"
        },
        "suite": {
          "description": "the suite class the test was run under",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "id",
        "name",
        "path",
        "suite",
        "file",
        "line"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Hotspot": {
      "properties": {
        "className": {
          "description": "the class of the hot method",
          "optional": false,
          "type": "string"
        },
        "method": {
          "description": "the hot method",
          "optional": false,
          "type": "string"
        },
        "samples": {
          "description": "execution samples landing in it",
          "optional": false,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "className",
        "method",
        "samples"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Strain": {
      "properties": {
        "coordinates": {
          "description": "the cell's coordinates",
          "items": {
            "properties": {
              "axis": {
                "description": "the axis's label",
                "optional": false,
                "type": "string"
              },
              "value": {
                "description": "the axis's value for this cell, as text",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "axis",
              "value"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "operations": {
          "description": "operations completed",
          "optional": false,
          "type": "integer"
        },
        "compliance": {
          "description": "the fraction of operations within the latency target, when one was declared",
          "optional": true,
          "type": "number"
        },
        "peakHeap": {
          "description": "peak heap, in bytes",
          "optional": false,
          "type": "integer"
        },
        "sustained": {
          "description": "whether the throughput was sustained at this concurrency",
          "optional": false,
          "type": "boolean"
        },
        "gcTime": {
          "description": "time spent in garbage collection, in nanoseconds",
          "optional": false,
          "type": "integer"
        },
        "concurrency": {
          "description": "concurrent workers",
          "optional": false,
          "type": "integer"
        },
        "p90": {
          "description": "90th percentile latency, in nanoseconds",
          "optional": true,
          "type": "integer"
        },
        "retained": {
          "description": "bytes retained after the measurement",
          "optional": false,
          "type": "integer"
        },
        "p99": {
          "description": "99th percentile latency, in nanoseconds",
          "optional": true,
          "type": "integer"
        },
        "nanoseconds": {
          "description": "the time measured, in nanoseconds",
          "optional": false,
          "type": "integer"
        },
        "p50": {
          "description": "median latency, in nanoseconds",
          "optional": true,
          "type": "integer"
        },
        "p999": {
          "description": "99.9th percentile latency, in nanoseconds",
          "optional": true,
          "type": "integer"
        },
        "gcCount": {
          "description": "garbage collections during the measurement",
          "optional": false,
          "type": "integer"
        },
        "allocation": {
          "description": "bytes allocated",
          "optional": false,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "coordinates",
        "concurrency",
        "operations",
        "nanoseconds",
        "allocation",
        "peakHeap",
        "retained",
        "gcCount",
        "gcTime",
        "sustained"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "ActiveTest": {
      "properties": {
        "ref": {
          "description": "the test or suite in flight",
          "properties": {
            "name": {
              "description": "the test's name",
              "optional": false,
              "type": "string"
            },
            "path": {
              "description": "the path of names or monikers from the root suite to the test",
              "items": {
                "optional": false,
                "type": "string"
              },
              "optional": false,
              "type": "array"
            },
            "line": {
              "description": "the line declaring the test",
              "optional": false,
              "type": "integer"
            },
            "moniker": {
              "description": "the test's moniker, a selection term, when it declares one",
              "optional": true,
              "type": "string"
            },
            "id": {
              "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
              "optional": false,
              "type": "string"
            },
            "file": {
              "description": "the source file declaring the test",
              "optional": false,
              "type": "string"
            },
            "suite": {
              "description": "the suite class the test was run under",
              "optional": false,
              "type": "string"
            }
          },
          "optional": false,
          "required": [
            "id",
            "name",
            "path",
            "suite",
            "file",
            "line"
          ],
          "additionalProperties": false,
          "type": "object"
        }
      },
      "optional": false,
      "required": [
        "ref"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Failures": {
      "properties": {
        "run": {
          "description": "the run's id",
          "optional": false,
          "type": "string"
        },
        "failures": {
          "description": "every failing test, with its diagnostics",
          "items": {
            "properties": {
              "comparison": {
                "description": "the structural comparison, row by row, when the test compared",
                "items": {
                  "properties": {
                    "depth": {
                      "description": "the row's depth in the structural comparison",
                      "optional": false,
                      "type": "integer"
                    },
                    "kind": {
                      "description": "`same`, `different`, or `collation` for a structure whose parts follow",
                      "optional": false,
                      "type": "string"
                    },
                    "difference": {
                      "description": "how they differ, or the structure's type name for a collation",
                      "optional": true,
                      "type": "string"
                    },
                    "right": {
                      "description": "the found side",
                      "optional": false,
                      "type": "string"
                    },
                    "left": {
                      "description": "the expected side",
                      "optional": false,
                      "type": "string"
                    },
                    "label": {
                      "description": "the field or element compared; empty at the root",
                      "optional": false,
                      "type": "string"
                    }
                  },
                  "optional": false,
                  "required": [
                    "depth",
                    "label",
                    "kind",
                    "left",
                    "right"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              },
              "captures": {
                "description": "values the test captured for its report",
                "items": {
                  "properties": {
                    "label": {
                      "description": "the captured value's label",
                      "optional": false,
                      "type": "string"
                    },
                    "value": {
                      "description": "the captured value, rendered",
                      "optional": false,
                      "type": "string"
                    }
                  },
                  "optional": false,
                  "required": [
                    "label",
                    "value"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              },
              "expected": {
                "description": "the expected value, rendered, when the test compared",
                "optional": true,
                "type": "string"
              },
              "ref": {
                "description": "the failing test",
                "properties": {
                  "name": {
                    "description": "the test's name",
                    "optional": false,
                    "type": "string"
                  },
                  "path": {
                    "description": "the path of names or monikers from the root suite to the test",
                    "items": {
                      "optional": false,
                      "type": "string"
                    },
                    "optional": false,
                    "type": "array"
                  },
                  "line": {
                    "description": "the line declaring the test",
                    "optional": false,
                    "type": "integer"
                  },
                  "moniker": {
                    "description": "the test's moniker, a selection term, when it declares one",
                    "optional": true,
                    "type": "string"
                  },
                  "id": {
                    "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
                    "optional": false,
                    "type": "string"
                  },
                  "file": {
                    "description": "the source file declaring the test",
                    "optional": false,
                    "type": "string"
                  },
                  "suite": {
                    "description": "the suite class the test was run under",
                    "optional": false,
                    "type": "string"
                  }
                },
                "optional": false,
                "required": [
                  "id",
                  "name",
                  "path",
                  "suite",
                  "file",
                  "line"
                ],
                "additionalProperties": false,
                "type": "object"
              },
              "found": {
                "description": "the found value, rendered, when the test compared",
                "optional": true,
                "type": "string"
              },
              "status": {
                "description": "`fail`, `throws`, `check-throws`, `mixed` or `aspire-fail`",
                "optional": false,
                "type": "string"
              },
              "message": {
                "description": "the failure's message, when the test gave one",
                "optional": true,
                "type": "string"
              },
              "trace": {
                "description": "the stack trace, when the test threw",
                "properties": {
                  "components": {
                    "description": "the chain of causes, the thrown exception first",
                    "items": {
                      "properties": {
                        "className": {
                          "description": "the exception's class",
                          "optional": false,
                          "type": "string"
                        },
                        "message": {
                          "description": "the exception's message",
                          "optional": false,
                          "type": "string"
                        },
                        "frames": {
                          "description": "the stack frames, outermost first",
                          "items": {
                            "properties": {
                              "className": {
                                "description": "the frame's class",
                                "optional": false,
                                "type": "string"
                              },
                              "method": {
                                "description": "the frame's method",
                                "optional": false,
                                "type": "string"
                              },
                              "file": {
                                "description": "the frame's source file",
                                "optional": false,
                                "type": "string"
                              },
                              "line": {
                                "description": "the frame's line, when known",
                                "optional": true,
                                "type": "integer"
                              }
                            },
                            "optional": false,
                            "required": [
                              "className",
                              "method",
                              "file"
                            ],
                            "additionalProperties": false,
                            "type": "object"
                          },
                          "optional": false,
                          "type": "array"
                        }
                      },
                      "optional": false,
                      "required": [
                        "className",
                        "message",
                        "frames"
                      ],
                      "additionalProperties": false,
                      "type": "object"
                    },
                    "optional": false,
                    "type": "array"
                  }
                },
                "optional": true,
                "required": [
                  "components"
                ],
                "additionalProperties": false,
                "type": "object"
              },
              "rerun": {
                "description": "the command which reruns exactly this test",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "ref",
              "status",
              "captures",
              "comparison",
              "rerun"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "fatals": {
          "description": "errors which ended a suite outright",
          "items": {
            "properties": {
              "trace": {
                "description": "the error which ended a suite",
                "properties": {
                  "components": {
                    "description": "the chain of causes, the thrown exception first",
                    "items": {
                      "properties": {
                        "className": {
                          "description": "the exception's class",
                          "optional": false,
                          "type": "string"
                        },
                        "message": {
                          "description": "the exception's message",
                          "optional": false,
                          "type": "string"
                        },
                        "frames": {
                          "description": "the stack frames, outermost first",
                          "items": {
                            "properties": {
                              "className": {
                                "description": "the frame's class",
                                "optional": false,
                                "type": "string"
                              },
                              "method": {
                                "description": "the frame's method",
                                "optional": false,
                                "type": "string"
                              },
                              "file": {
                                "description": "the frame's source file",
                                "optional": false,
                                "type": "string"
                              },
                              "line": {
                                "description": "the frame's line, when known",
                                "optional": true,
                                "type": "integer"
                              }
                            },
                            "optional": false,
                            "required": [
                              "className",
                              "method",
                              "file"
                            ],
                            "additionalProperties": false,
                            "type": "object"
                          },
                          "optional": false,
                          "type": "array"
                        }
                      },
                      "optional": false,
                      "required": [
                        "className",
                        "message",
                        "frames"
                      ],
                      "additionalProperties": false,
                      "type": "object"
                    },
                    "optional": false,
                    "type": "array"
                  }
                },
                "optional": false,
                "required": [
                  "components"
                ],
                "additionalProperties": false,
                "type": "object"
              },
              "affected": {
                "description": "the tests that were running when it did",
                "items": {
                  "properties": {
                    "name": {
                      "description": "the test's name",
                      "optional": false,
                      "type": "string"
                    },
                    "path": {
                      "description": "the path of names or monikers from the root suite to the test",
                      "items": {
                        "optional": false,
                        "type": "string"
                      },
                      "optional": false,
                      "type": "array"
                    },
                    "line": {
                      "description": "the line declaring the test",
                      "optional": false,
                      "type": "integer"
                    },
                    "moniker": {
                      "description": "the test's moniker, a selection term, when it declares one",
                      "optional": true,
                      "type": "string"
                    },
                    "id": {
                      "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
                      "optional": false,
                      "type": "string"
                    },
                    "file": {
                      "description": "the source file declaring the test",
                      "optional": false,
                      "type": "string"
                    },
                    "suite": {
                      "description": "the suite class the test was run under",
                      "optional": false,
                      "type": "string"
                    }
                  },
                  "optional": false,
                  "required": [
                    "id",
                    "name",
                    "path",
                    "suite",
                    "file",
                    "line"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              }
            },
            "optional": false,
            "required": [
              "trace",
              "affected"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "nothingMatched": {
          "description": "whether the selection admitted no test at all",
          "optional": false,
          "type": "boolean"
        }
      },
      "optional": false,
      "required": [
        "run",
        "failures",
        "fatals",
        "nothingMatched"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "TestResult": {
      "properties": {
        "benchmarks": {
          "description": "each cell's benchmark record, for a `bench`",
          "items": {
            "properties": {
              "coordinates": {
                "description": "the cell's coordinates",
                "items": {
                  "properties": {
                    "axis": {
                      "description": "the axis's label",
                      "optional": false,
                      "type": "string"
                    },
                    "value": {
                      "description": "the axis's value for this cell, as text",
                      "optional": false,
                      "type": "string"
                    }
                  },
                  "optional": false,
                  "required": [
                    "axis",
                    "value"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              },
              "operationRate": {
                "description": "the operation rate, when derived",
                "optional": true,
                "type": "string"
              },
              "runs": {
                "description": "measurement runs",
                "optional": false,
                "type": "integer"
              },
              "mean": {
                "description": "mean time per operation, in nanoseconds",
                "optional": false,
                "type": "number"
              },
              "min": {
                "description": "fastest run, in nanoseconds per operation",
                "optional": false,
                "type": "number"
              },
              "confidence": {
                "description": "confidence, as a percentage",
                "optional": false,
                "type": "integer"
              },
              "allocation": {
                "description": "bytes allocated per operation, when measured",
                "optional": true,
                "type": "integer"
              },
              "max": {
                "description": "slowest run, in nanoseconds per operation",
                "optional": false,
                "type": "number"
              },
              "operationSize": {
                "description": "the size of one operation, when declared",
                "optional": true,
                "type": "string"
              },
              "sd": {
                "description": "standard deviation, in nanoseconds",
                "optional": false,
                "type": "number"
              },
              "iterations": {
                "description": "operations timed",
                "optional": false,
                "type": "integer"
              },
              "nanoseconds": {
                "description": "the time measured, in nanoseconds",
                "optional": false,
                "type": "integer"
              }
            },
            "optional": false,
            "required": [
              "coordinates",
              "nanoseconds",
              "iterations",
              "runs",
              "mean",
              "min",
              "max",
              "sd",
              "confidence"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "rerun": {
          "description": "the command which reruns exactly this test",
          "optional": false,
          "type": "string"
        },
        "ref": {
          "description": "the test",
          "properties": {
            "name": {
              "description": "the test's name",
              "optional": false,
              "type": "string"
            },
            "path": {
              "description": "the path of names or monikers from the root suite to the test",
              "items": {
                "optional": false,
                "type": "string"
              },
              "optional": false,
              "type": "array"
            },
            "line": {
              "description": "the line declaring the test",
              "optional": false,
              "type": "integer"
            },
            "moniker": {
              "description": "the test's moniker, a selection term, when it declares one",
              "optional": true,
              "type": "string"
            },
            "id": {
              "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
              "optional": false,
              "type": "string"
            },
            "file": {
              "description": "the source file declaring the test",
              "optional": false,
              "type": "string"
            },
            "suite": {
              "description": "the suite class the test was run under",
              "optional": false,
              "type": "string"
            }
          },
          "optional": false,
          "required": [
            "id",
            "name",
            "path",
            "suite",
            "file",
            "line"
          ],
          "additionalProperties": false,
          "type": "object"
        },
        "strains": {
          "description": "each concurrency level's record, for a `stress`",
          "items": {
            "properties": {
              "coordinates": {
                "description": "the cell's coordinates",
                "items": {
                  "properties": {
                    "axis": {
                      "description": "the axis's label",
                      "optional": false,
                      "type": "string"
                    },
                    "value": {
                      "description": "the axis's value for this cell, as text",
                      "optional": false,
                      "type": "string"
                    }
                  },
                  "optional": false,
                  "required": [
                    "axis",
                    "value"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              },
              "operations": {
                "description": "operations completed",
                "optional": false,
                "type": "integer"
              },
              "compliance": {
                "description": "the fraction of operations within the latency target, when one was declared",
                "optional": true,
                "type": "number"
              },
              "peakHeap": {
                "description": "peak heap, in bytes",
                "optional": false,
                "type": "integer"
              },
              "sustained": {
                "description": "whether the throughput was sustained at this concurrency",
                "optional": false,
                "type": "boolean"
              },
              "gcTime": {
                "description": "time spent in garbage collection, in nanoseconds",
                "optional": false,
                "type": "integer"
              },
              "concurrency": {
                "description": "concurrent workers",
                "optional": false,
                "type": "integer"
              },
              "p90": {
                "description": "90th percentile latency, in nanoseconds",
                "optional": true,
                "type": "integer"
              },
              "retained": {
                "description": "bytes retained after the measurement",
                "optional": false,
                "type": "integer"
              },
              "p99": {
                "description": "99th percentile latency, in nanoseconds",
                "optional": true,
                "type": "integer"
              },
              "nanoseconds": {
                "description": "the time measured, in nanoseconds",
                "optional": false,
                "type": "integer"
              },
              "p50": {
                "description": "median latency, in nanoseconds",
                "optional": true,
                "type": "integer"
              },
              "p999": {
                "description": "99.9th percentile latency, in nanoseconds",
                "optional": true,
                "type": "integer"
              },
              "gcCount": {
                "description": "garbage collections during the measurement",
                "optional": false,
                "type": "integer"
              },
              "allocation": {
                "description": "bytes allocated",
                "optional": false,
                "type": "integer"
              }
            },
            "optional": false,
            "required": [
              "coordinates",
              "concurrency",
              "operations",
              "nanoseconds",
              "allocation",
              "peakHeap",
              "retained",
              "gcCount",
              "gcTime",
              "sustained"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "status": {
          "description": "the test's collective status: `pass`, `fail`, `throws`, `check-throws`, `aspire-pass`, `aspire-fail`, `mixed` (its cells disagree), or `bench`, `stress`, `profile` for a measurement",
          "optional": false,
          "type": "string"
        },
        "hotspots": {
          "description": "the hot methods, for a `profile`",
          "items": {
            "properties": {
              "className": {
                "description": "the class of the hot method",
                "optional": false,
                "type": "string"
              },
              "method": {
                "description": "the hot method",
                "optional": false,
                "type": "string"
              },
              "samples": {
                "description": "execution samples landing in it",
                "optional": false,
                "type": "integer"
              }
            },
            "optional": false,
            "required": [
              "className",
              "method",
              "samples"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "kind": {
          "description": "`check`, `bench`, `stress` or `profile`",
          "optional": false,
          "type": "string"
        },
        "completions": {
          "description": "each cell's outcome, for a `check`",
          "items": {
            "properties": {
              "coordinates": {
                "description": "the cell's coordinates; empty for a test without axes",
                "items": {
                  "properties": {
                    "axis": {
                      "description": "the axis's label",
                      "optional": false,
                      "type": "string"
                    },
                    "value": {
                      "description": "the axis's value for this cell, as text",
                      "optional": false,
                      "type": "string"
                    }
                  },
                  "optional": false,
                  "required": [
                    "axis",
                    "value"
                  ],
                  "additionalProperties": false,
                  "type": "object"
                },
                "optional": false,
                "type": "array"
              },
              "outcome": {
                "description": "`pass`, `fail`, `throws`, `check-throws`, `aspire-pass` or `aspire-fail`",
                "optional": false,
                "type": "string"
              },
              "nanoseconds": {
                "description": "how long the cell took, in nanoseconds",
                "optional": false,
                "type": "integer"
              }
            },
            "optional": false,
            "required": [
              "coordinates",
              "outcome",
              "nanoseconds"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        }
      },
      "optional": false,
      "required": [
        "ref",
        "kind",
        "status",
        "completions",
        "benchmarks",
        "strains",
        "hotspots",
        "rerun"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "WorkerSession": {
      "properties": {
        "controller": {
          "description": "the hostname of the controller whose run this daemon is working",
          "optional": false,
          "type": "string"
        },
        "current": {
          "description": "the suite running now",
          "optional": true,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "controller"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Comparison": {
      "properties": {
        "depth": {
          "description": "the row's depth in the structural comparison",
          "optional": false,
          "type": "integer"
        },
        "kind": {
          "description": "`same`, `different`, or `collation` for a structure whose parts follow",
          "optional": false,
          "type": "string"
        },
        "difference": {
          "description": "how they differ, or the structure's type name for a collation",
          "optional": true,
          "type": "string"
        },
        "right": {
          "description": "the found side",
          "optional": false,
          "type": "string"
        },
        "left": {
          "description": "the expected side",
          "optional": false,
          "type": "string"
        },
        "label": {
          "description": "the field or element compared; empty at the root",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "depth",
        "label",
        "kind",
        "left",
        "right"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Coordinate": {
      "properties": {
        "axis": {
          "description": "the axis's label",
          "optional": false,
          "type": "string"
        },
        "value": {
          "description": "the axis's value for this cell, as text",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "axis",
        "value"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "ActiveRun": {
      "properties": {
        "suitesDone": {
          "description": "suites finished so far",
          "optional": false,
          "type": "integer"
        },
        "forked": {
          "description": "the pid of the suite's own JVM, under `--fork`",
          "optional": true,
          "type": "integer"
        },
        "suitesTotal": {
          "description": "suites scheduled",
          "optional": false,
          "type": "integer"
        },
        "current": {
          "description": "the suite running now",
          "optional": true,
          "type": "string"
        },
        "summary": {
          "description": "the run in summary",
          "properties": {
            "duration": {
              "description": "the run's length in milliseconds, once finished",
              "optional": true,
              "type": "integer"
            },
            "suites": {
              "description": "how many suites the run scheduled",
              "optional": false,
              "type": "integer"
            },
            "invoker": {
              "description": "who launched the run: `human`, `claude`, `codex`, or `remote` for another fume",
              "optional": false,
              "type": "string"
            },
            "finished": {
              "description": "when the run finished; absent while it is in flight",
              "format": "date-time",
              "optional": true,
              "type": "string"
            },
            "outcome": {
              "description": "`passed`, `failed` or `aborted`; absent while the run is in flight",
              "optional": true,
              "type": "string"
            },
            "id": {
              "description": "the run's id, stable across daemon restarts; `last` names the newest run in any tool",
              "optional": false,
              "type": "string"
            },
            "machine": {
              "description": "the machine the suites ran on",
              "optional": false,
              "type": "string"
            },
            "workspace": {
              "description": "the working directory `fume` was invoked from",
              "optional": false,
              "type": "string"
            },
            "selection": {
              "description": "the selection terms forwarded to each suite",
              "items": {
                "optional": false,
                "type": "string"
              },
              "optional": false,
              "type": "array"
            },
            "started": {
              "description": "when the run started, ISO 8601 in UTC",
              "format": "date-time",
              "optional": false,
              "type": "string"
            },
            "totals": {
              "description": "the run's totals, once finished",
              "properties": {
                "passed": {
                  "description": "tests which passed, including measurements which completed",
                  "optional": false,
                  "type": "integer"
                },
                "failed": {
                  "description": "tests which failed, threw, or had a failing check",
                  "optional": false,
                  "type": "integer"
                },
                "aspirePassed": {
                  "description": "aspirational tests which passed",
                  "optional": false,
                  "type": "integer"
                },
                "aspireFailed": {
                  "description": "aspirational tests which failed, which does not fail the run",
                  "optional": false,
                  "type": "integer"
                }
              },
              "optional": true,
              "required": [
                "passed",
                "failed",
                "aspirePassed",
                "aspireFailed"
              ],
              "additionalProperties": false,
              "type": "object"
            }
          },
          "optional": false,
          "required": [
            "id",
            "workspace",
            "invoker",
            "machine",
            "started",
            "selection",
            "suites"
          ],
          "additionalProperties": false,
          "type": "object"
        },
        "active": {
          "description": "tests and suites in flight",
          "items": {
            "properties": {
              "ref": {
                "description": "the test or suite in flight",
                "properties": {
                  "name": {
                    "description": "the test's name",
                    "optional": false,
                    "type": "string"
                  },
                  "path": {
                    "description": "the path of names or monikers from the root suite to the test",
                    "items": {
                      "optional": false,
                      "type": "string"
                    },
                    "optional": false,
                    "type": "array"
                  },
                  "line": {
                    "description": "the line declaring the test",
                    "optional": false,
                    "type": "integer"
                  },
                  "moniker": {
                    "description": "the test's moniker, a selection term, when it declares one",
                    "optional": true,
                    "type": "string"
                  },
                  "id": {
                    "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
                    "optional": false,
                    "type": "string"
                  },
                  "file": {
                    "description": "the source file declaring the test",
                    "optional": false,
                    "type": "string"
                  },
                  "suite": {
                    "description": "the suite class the test was run under",
                    "optional": false,
                    "type": "string"
                  }
                },
                "optional": false,
                "required": [
                  "id",
                  "name",
                  "path",
                  "suite",
                  "file",
                  "line"
                ],
                "additionalProperties": false,
                "type": "object"
              }
            },
            "optional": false,
            "required": [
              "ref"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        }
      },
      "optional": false,
      "required": [
        "summary",
        "suitesDone",
        "suitesTotal",
        "active"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Failure": {
      "properties": {
        "comparison": {
          "description": "the structural comparison, row by row, when the test compared",
          "items": {
            "properties": {
              "depth": {
                "description": "the row's depth in the structural comparison",
                "optional": false,
                "type": "integer"
              },
              "kind": {
                "description": "`same`, `different`, or `collation` for a structure whose parts follow",
                "optional": false,
                "type": "string"
              },
              "difference": {
                "description": "how they differ, or the structure's type name for a collation",
                "optional": true,
                "type": "string"
              },
              "right": {
                "description": "the found side",
                "optional": false,
                "type": "string"
              },
              "left": {
                "description": "the expected side",
                "optional": false,
                "type": "string"
              },
              "label": {
                "description": "the field or element compared; empty at the root",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "depth",
              "label",
              "kind",
              "left",
              "right"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "captures": {
          "description": "values the test captured for its report",
          "items": {
            "properties": {
              "label": {
                "description": "the captured value's label",
                "optional": false,
                "type": "string"
              },
              "value": {
                "description": "the captured value, rendered",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "label",
              "value"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "expected": {
          "description": "the expected value, rendered, when the test compared",
          "optional": true,
          "type": "string"
        },
        "ref": {
          "description": "the failing test",
          "properties": {
            "name": {
              "description": "the test's name",
              "optional": false,
              "type": "string"
            },
            "path": {
              "description": "the path of names or monikers from the root suite to the test",
              "items": {
                "optional": false,
                "type": "string"
              },
              "optional": false,
              "type": "array"
            },
            "line": {
              "description": "the line declaring the test",
              "optional": false,
              "type": "integer"
            },
            "moniker": {
              "description": "the test's moniker, a selection term, when it declares one",
              "optional": true,
              "type": "string"
            },
            "id": {
              "description": "the test's 6-hex id, a selection term for `fume run`; empty for a suite",
              "optional": false,
              "type": "string"
            },
            "file": {
              "description": "the source file declaring the test",
              "optional": false,
              "type": "string"
            },
            "suite": {
              "description": "the suite class the test was run under",
              "optional": false,
              "type": "string"
            }
          },
          "optional": false,
          "required": [
            "id",
            "name",
            "path",
            "suite",
            "file",
            "line"
          ],
          "additionalProperties": false,
          "type": "object"
        },
        "found": {
          "description": "the found value, rendered, when the test compared",
          "optional": true,
          "type": "string"
        },
        "status": {
          "description": "`fail`, `throws`, `check-throws`, `mixed` or `aspire-fail`",
          "optional": false,
          "type": "string"
        },
        "message": {
          "description": "the failure's message, when the test gave one",
          "optional": true,
          "type": "string"
        },
        "trace": {
          "description": "the stack trace, when the test threw",
          "properties": {
            "components": {
              "description": "the chain of causes, the thrown exception first",
              "items": {
                "properties": {
                  "className": {
                    "description": "the exception's class",
                    "optional": false,
                    "type": "string"
                  },
                  "message": {
                    "description": "the exception's message",
                    "optional": false,
                    "type": "string"
                  },
                  "frames": {
                    "description": "the stack frames, outermost first",
                    "items": {
                      "properties": {
                        "className": {
                          "description": "the frame's class",
                          "optional": false,
                          "type": "string"
                        },
                        "method": {
                          "description": "the frame's method",
                          "optional": false,
                          "type": "string"
                        },
                        "file": {
                          "description": "the frame's source file",
                          "optional": false,
                          "type": "string"
                        },
                        "line": {
                          "description": "the frame's line, when known",
                          "optional": true,
                          "type": "integer"
                        }
                      },
                      "optional": false,
                      "required": [
                        "className",
                        "method",
                        "file"
                      ],
                      "additionalProperties": false,
                      "type": "object"
                    },
                    "optional": false,
                    "type": "array"
                  }
                },
                "optional": false,
                "required": [
                  "className",
                  "message",
                  "frames"
                ],
                "additionalProperties": false,
                "type": "object"
              },
              "optional": false,
              "type": "array"
            }
          },
          "optional": true,
          "required": [
            "components"
          ],
          "additionalProperties": false,
          "type": "object"
        },
        "rerun": {
          "description": "the command which reruns exactly this test",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "ref",
        "status",
        "captures",
        "comparison",
        "rerun"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Service": {
      "properties": {
        "name": {
          "description": "`dashboard`, `listener` or `mcp`",
          "optional": false,
          "type": "string"
        },
        "port": {
          "description": "the port it serves on",
          "optional": false,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "name",
        "port"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "IndexedSuite": {
      "properties": {
        "suite": {
          "description": "the suite's class name, as `fume run --suite` takes it",
          "optional": false,
          "type": "string"
        },
        "indexed": {
          "description": "whether the classpath's static index covers the suite; a suite it does not is listed only by running it",
          "optional": false,
          "type": "boolean"
        },
        "tests": {
          "description": "the tests the index declares for the suite",
          "optional": false,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "suite",
        "indexed",
        "tests"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Daemon": {
      "properties": {
        "pid": {
          "description": "the daemon's pid",
          "optional": false,
          "type": "integer"
        },
        "version": {
          "description": "fume's version",
          "optional": false,
          "type": "string"
        },
        "started": {
          "description": "when the daemon started",
          "format": "date-time",
          "optional": false,
          "type": "string"
        },
        "runsDirectory": {
          "description": "where runs are persisted",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "pid",
        "version",
        "started",
        "runsDirectory"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "IndexedTest": {
      "properties": {
        "path": {
          "description": "the path of names or monikers from the root suite to the test",
          "items": {
            "optional": false,
            "type": "string"
          },
          "optional": false,
          "type": "array"
        },
        "line": {
          "description": "the line",
          "optional": false,
          "type": "integer"
        },
        "tags": {
          "description": "the test's tags",
          "items": {
            "optional": false,
            "type": "string"
          },
          "optional": false,
          "type": "array"
        },
        "moniker": {
          "description": "the test's moniker, when it declares one",
          "optional": true,
          "type": "string"
        },
        "id": {
          "description": "the test's 6-hex id; absent when its name is only known at runtime",
          "optional": true,
          "type": "string"
        },
        "file": {
          "description": "the source file",
          "optional": false,
          "type": "string"
        },
        "kind": {
          "description": "`check`, `bench`, `stress` or `profile`",
          "optional": false,
          "type": "string"
        },
        "suite": {
          "description": "the suite class declaring the test",
          "optional": false,
          "type": "string"
        },
        "dynamic": {
          "description": "whether part of the test's name is computed at runtime",
          "optional": false,
          "type": "boolean"
        },
        "spread": {
          "description": "whether the test is declared over axes, whose cells the index does not know",
          "optional": false,
          "type": "boolean"
        },
        "name": {
          "description": "the test's name, with `*` for each part computed at runtime",
          "optional": false,
          "type": "string"
        }
      },
      "optional": false,
      "required": [
        "path",
        "name",
        "kind",
        "tags",
        "suite",
        "file",
        "line",
        "spread",
        "dynamic"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "Totals": {
      "properties": {
        "passed": {
          "description": "tests which passed, including measurements which completed",
          "optional": false,
          "type": "integer"
        },
        "failed": {
          "description": "tests which failed, threw, or had a failing check",
          "optional": false,
          "type": "integer"
        },
        "aspirePassed": {
          "description": "aspirational tests which passed",
          "optional": false,
          "type": "integer"
        },
        "aspireFailed": {
          "description": "aspirational tests which failed, which does not fail the run",
          "optional": false,
          "type": "integer"
        }
      },
      "optional": false,
      "required": [
        "passed",
        "failed",
        "aspirePassed",
        "aspireFailed"
      ],
      "additionalProperties": false,
      "type": "object"
    },
    "RunDetail": {
      "properties": {
        "suites": {
          "description": "each suite that has run so far",
          "items": {
            "properties": {
              "captured": {
                "description": "whether the suite printed anything outside its report; see the `captured` tool",
                "optional": false,
                "type": "boolean"
              },
              "finished": {
                "description": "when the suite finished",
                "format": "date-time",
                "optional": false,
                "type": "string"
              },
              "passed": {
                "description": "whether the suite passed",
                "optional": false,
                "type": "boolean"
              },
              "started": {
                "description": "when the suite started",
                "format": "date-time",
                "optional": false,
                "type": "string"
              },
              "totals": {
                "description": "the suite's totals, when it streamed its events",
                "properties": {
                  "passed": {
                    "description": "tests which passed, including measurements which completed",
                    "optional": false,
                    "type": "integer"
                  },
                  "failed": {
                    "description": "tests which failed, threw, or had a failing check",
                    "optional": false,
                    "type": "integer"
                  },
                  "aspirePassed": {
                    "description": "aspirational tests which passed",
                    "optional": false,
                    "type": "integer"
                  },
                  "aspireFailed": {
                    "description": "aspirational tests which failed, which does not fail the run",
                    "optional": false,
                    "type": "integer"
                  }
                },
                "optional": true,
                "required": [
                  "passed",
                  "failed",
                  "aspirePassed",
                  "aspireFailed"
                ],
                "additionalProperties": false,
                "type": "object"
              },
              "suite": {
                "description": "the suite's class name",
                "optional": false,
                "type": "string"
              }
            },
            "optional": false,
            "required": [
              "suite",
              "passed",
              "started",
              "finished",
              "captured"
            ],
            "additionalProperties": false,
            "type": "object"
          },
          "optional": false,
          "type": "array"
        },
        "scheduled": {
          "description": "the suites the run scheduled, in order",
          "items": {
            "optional": false,
            "type": "string"
          },
          "optional": false,
          "type": "array"
        },
        "classpath": {
          "description": "the classpath the suites were loaded from",
          "items": {
            "optional": false,
            "type": "string"
          },
          "optional": false,
          "type": "array"
        },
        "results": {
          "description": "whether per-test results can be served: `available`, `partial` (some suites could not stream), `incompatible` (the suites' event schema differs from this fume's), or `none`",
          "optional": false,
          "type": "string"
        },
        "current": {
          "description": "the suite running now, if any",
          "optional": true,
          "type": "string"
        },
        "summary": {
          "description": "the run in summary",
          "properties": {
            "duration": {
              "description": "the run's length in milliseconds, once finished",
              "optional": true,
              "type": "integer"
            },
            "suites": {
              "description": "how many suites the run scheduled",
              "optional": false,
              "type": "integer"
            },
            "invoker": {
              "description": "who launched the run: `human`, `claude`, `codex`, or `remote` for another fume",
              "optional": false,
              "type": "string"
            },
            "finished": {
              "description": "when the run finished; absent while it is in flight",
              "format": "date-time",
              "optional": true,
              "type": "string"
            },
            "outcome": {
              "description": "`passed`, `failed` or `aborted`; absent while the run is in flight",
              "optional": true,
              "type": "string"
            },
            "id": {
              "description": "the run's id, stable across daemon restarts; `last` names the newest run in any tool",
              "optional": false,
              "type": "string"
            },
            "machine": {
              "description": "the machine the suites ran on",
              "optional": false,
              "type": "string"
            },
            "workspace": {
              "description": "the working directory `fume` was invoked from",
              "optional": false,
              "type": "string"
            },
            "selection": {
              "description": "the selection terms forwarded to each suite",
              "items": {
                "optional": false,
                "type": "string"
              },
              "optional": false,
              "type": "array"
            },
            "started": {
              "description": "when the run started, ISO 8601 in UTC",
              "format": "date-time",
              "optional": false,
              "type": "string"
            },
            "totals": {
              "description": "the run's totals, once finished",
              "properties": {
                "passed": {
                  "description": "tests which passed, including measurements which completed",
                  "optional": false,
                  "type": "integer"
                },
                "failed": {
                  "description": "tests which failed, threw, or had a failing check",
                  "optional": false,
                  "type": "integer"
                },
                "aspirePassed": {
                  "description": "aspirational tests which passed",
                  "optional": false,
                  "type": "integer"
                },
                "aspireFailed": {
                  "description": "aspirational tests which failed, which does not fail the run",
                  "optional": false,
                  "type": "integer"
                }
              },
              "optional": true,
              "required": [
                "passed",
                "failed",
                "aspirePassed",
                "aspireFailed"
              ],
              "additionalProperties": false,
              "type": "object"
            }
          },
          "optional": false,
          "required": [
            "id",
            "workspace",
            "invoker",
            "machine",
            "started",
            "selection",
            "suites"
          ],
          "additionalProperties": false,
          "type": "object"
        }
      },
      "optional": false,
      "required": [
        "summary",
        "classpath",
        "scheduled",
        "suites",
        "results"
      ],
      "additionalProperties": false,
      "type": "object"
    }
  },
  "optional": false,
  "additionalProperties": false,
  "type": "object"
}
```
