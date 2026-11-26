# API design

The API is same-origin from the frontend via a Next.js proxy. Project resources are scoped by UUID. For CLI and local integration use `http://127.0.0.1:8080/api`.

| Operation | Route relative to /api |
| --- | --- |
| Health and projects | `GET /health`, `GET/POST /projects`, `PUT/DELETE /projects/{project}` |
| Manage metadata | `GET/POST /projects/{project}/resources/{kind}`, `PUT/DELETE …/{id}` |
| Test a source | `POST /projects/{project}/connections/{id}/test` |
| Discover and snapshot | `GET /projects/{project}/catalog/{connection\|sandbox}/{id}?exact=false`, `POST …/snapshot` |
| Provision or manage sandbox | `POST /projects/{project}/sandboxes`, `POST …/{id}/{start\|stop\|restart\|reset\|delete\|import}` |
| List, toggle or build indexes | `GET /projects/{project}/sandboxes/{id}/indexes`, `POST …/{toggle\|build\|remove}` |
| Generate, execute, benchmark, load | `POST /projects/{project}/{generate\|execute\|benchmarks\|loads}` |
| Monitor or cancel | `GET /projects/{project}/jobs/{id}`, `GET …/events`, `POST …/cancel` |
| View SQL preview | `GET /projects/{project}/jobs/{id}/preview` |
| Report and download | `GET /projects/{project}/reports/{id}`, `GET …/export?format=json\|csv\|html` |
| Fetch a load report | `GET /projects/{project}/loads/{jobId}` |
| Set up the disposable practice project | `POST /demo` |

Resource collections include connection, sandbox, schema, dataset, query, index-config, and job. The mutable metadata collections are connections, datasets, saved queries and index configurations. Jobs return queued/running/completed/failed/cancelled states. SSE sends an event named `progress` with the current job representation about every 750ms, and closes on a terminal state. API errors use `{"message":"…"}`, while the HTTP status communicates the category. Source passwords are write-only.
